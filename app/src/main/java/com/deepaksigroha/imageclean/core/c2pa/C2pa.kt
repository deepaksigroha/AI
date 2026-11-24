package com.deepaksigroha.imageclean.core.c2pa

import com.deepaksigroha.imageclean.core.Bytes
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Date

/** The four user-facing credential states required by the specification. */
enum class C2paState(val title: String) {
    VERIFIED("C2PA credentials present and verified"),
    INVALID("C2PA credentials present but invalid"),
    UNVERIFIABLE("C2PA credentials present but unverifiable"),
    NONE("No detectable C2PA credentials"),
}

enum class CheckResult { PASS, FAIL, NOT_CHECKED }

data class C2paCheck(val name: String, val result: CheckResult, val detail: String)

data class C2paAction(
    val action: String,
    val digitalSourceType: String?,
    val softwareAgent: String?,
    val description: String?,
    val whenTime: String?,
)

data class C2paManifest(
    val label: String,
    val isActive: Boolean,
    val claimGenerator: String?,
    val title: String?,
    val format: String?,
    val assertionLabels: List<String>,
    val actions: List<C2paAction>,
    val ingredients: List<String>,
    val author: String?,
    val trainingMining: String?,
    val signerSubject: String?,
    val signerIssuer: String?,
    val signingAlg: String?,
    val claimJson: String,
)

data class C2paReport(
    val state: C2paState,
    val summary: String,
    val manifests: List<C2paManifest>,
    val checks: List<C2paCheck>,
    val aiSourceTypes: List<String>,
) {
    val active get() = manifests.firstOrNull { it.isActive }

    companion object {
        val NONE = C2paReport(C2paState.NONE, "No C2PA manifest store was found in this file.", emptyList(), emptyList(), emptyList())
    }
}

/** Decides whether a signer certificate chain is anchored in a trusted root. */
fun interface TrustEvaluator {
    /** @return null if trusted, otherwise a human-readable reason. */
    fun evaluate(chain: List<X509Certificate>): String?
}

/**
 * Reads and validates a C2PA manifest store.
 *
 * Implemented validation (C2PA 1.x/2.x, JPEG/PNG/WebP data-hash binding):
 *  - COSE_Sign1 signature over the claim (ES256/384/512, PS256/384/512, Ed25519)
 *  - certificate chain integrity (each certificate signed by the next) and validity period
 *  - trust anchoring through [TrustEvaluator] (device CA store + user-imported anchors)
 *  - hashed-URI integrity of each referenced assertion
 *  - hard binding: c2pa.hash.data exclusion-range SHA-2 hash over the asset bytes
 * Not implemented (reported as NOT_CHECKED → result "unverifiable"): BMFF/collection hash
 * bindings, RFC 3161 timestamp validation, OCSP revocation, ingredient manifest recursion.
 */
class C2paReader(private val trust: TrustEvaluator) {

    fun read(store: ByteArray?, asset: ByteArray): C2paReport {
        if (store == null) return C2paReport.NONE
        val root = Jumbf.parse(store)
            ?: return C2paReport(C2paState.INVALID, "A C2PA block exists but its JUMBF structure is corrupt.", emptyList(),
                listOf(C2paCheck("Manifest store structure", CheckResult.FAIL, "JUMBF parse error")), emptyList())
        val manifestBoxes = root.childrenSuperboxes()
        if (manifestBoxes.isEmpty()) return C2paReport(C2paState.INVALID, "The C2PA manifest store is empty.", emptyList(),
            listOf(C2paCheck("Manifest store structure", CheckResult.FAIL, "No manifests")), emptyList())

        val checks = ArrayList<C2paCheck>()
        val manifests = ArrayList<C2paManifest>()
        val aiTypes = ArrayList<String>()
        val activeBox = manifestBoxes.last()
        var invalid = false
        var unverifiable = false

        for (mb in manifestBoxes) {
            val isActive = mb === activeBox
            val claimBox = mb.child("c2pa.claim.v2") ?: mb.child("c2pa.claim")
            val sigBox = mb.child("c2pa.signature")
            val assertionsBox = mb.child("c2pa.assertions")
            val claimBytes = claimBox?.contentBox()?.let { store.copyOfRange(it.payloadStart, it.end) }
            val claim = claimBytes?.let { runCatching { CborDecoder.decode(it) }.getOrNull() }

            val generator = claim?.get("claim_generator")?.asText()
                ?: claim?.get("claim_generator_info")?.let { info ->
                    val first = info.asArr()?.firstOrNull() ?: info
                    listOfNotNull(first["name"]?.asText(), first["version"]?.asText()).joinToString(" ").ifBlank { null }
                }

            // ---- assertions
            val labels = ArrayList<String>()
            val actions = ArrayList<C2paAction>()
            val ingredients = ArrayList<String>()
            var author: String? = null
            var training: String? = null
            assertionsBox?.childrenSuperboxes()?.forEach { a ->
                val label = a.label ?: return@forEach
                labels += label
                val content = a.contentBox() ?: return@forEach
                val data = store.copyOfRange(content.payloadStart, content.end)
                val base = label.substringBefore("__")
                when {
                    base.startsWith("c2pa.actions") -> runCatching { CborDecoder.decode(data) }.getOrNull()?.let { v ->
                        v["actions"]?.asArr()?.forEach { act ->
                            val agent = act["softwareAgent"]?.let { it.asText() ?: it["name"]?.asText() }
                            val dst = act["digitalSourceType"]?.asText()
                            dst?.let { aiTypes += it }
                            actions += C2paAction(
                                act["action"]?.asText() ?: "?", dst, agent,
                                act["description"]?.asText() ?: act["parameters"]?.get("description")?.asText(),
                                act["when"]?.asText(),
                            )
                        }
                    }
                    base.startsWith("c2pa.ingredient") -> runCatching { CborDecoder.decode(data) }.getOrNull()?.let { v ->
                        ingredients += listOfNotNull(v["dc:title"]?.asText(), v["relationship"]?.asText()?.let { "($it)" }).joinToString(" ")
                            .ifBlank { "ingredient" }
                    }
                    base == "stds.schema-org.CreativeWork" -> {
                        val json = String(data, Charsets.UTF_8)
                        author = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                    }
                    base.startsWith("c2pa.training-mining") || base.startsWith("cawg.training-mining") -> {
                        training = runCatching { CborDecoder.decode(data).render(maxLen = 600) }.getOrNull() ?: String(data, Charsets.UTF_8).take(600)
                    }
                }
            }

            // ---- signature
            var signerSubject: String? = null
            var signerIssuer: String? = null
            var algName: String? = null
            if (isActive) {
                if (claim == null || claimBytes == null) {
                    checks += C2paCheck("Claim", CheckResult.FAIL, "Claim box missing or not valid CBOR"); invalid = true
                } else if (sigBox == null) {
                    checks += C2paCheck("Claim signature", CheckResult.FAIL, "Signature box missing"); invalid = true
                } else {
                    val sigContent = sigBox.contentBox()
                    val sig = sigContent?.let { verifySignature(store.copyOfRange(it.payloadStart, it.end), claimBytes) }
                    if (sig == null) {
                        checks += C2paCheck("Claim signature", CheckResult.FAIL, "COSE_Sign1 structure could not be read"); invalid = true
                    } else {
                        signerSubject = sig.subject; signerIssuer = sig.issuer; algName = sig.alg
                        checks += sig.checks
                        if (sig.checks.any { it.result == CheckResult.FAIL && it.name != "Signer trust" && it.name != "Certificate validity" }) invalid = true
                        if (sig.checks.any { it.result != CheckResult.PASS }) unverifiable = true
                    }

                    // ---- assertion hashes (hashed URIs)
                    val claimAlg = claim["alg"]?.asText() ?: "sha256"
                    val refs = (claim["created_assertions"]?.asArr().orEmpty() + claim["gathered_assertions"]?.asArr().orEmpty())
                        .ifEmpty { claim["assertions"]?.asArr().orEmpty() }
                    var okCount = 0; var badCount = 0; var missing = 0
                    for (ref in refs) {
                        val url = ref["url"]?.asText() ?: continue
                        val expected = ref["hash"]?.asBytes() ?: continue
                        val alg = ref["alg"]?.asText() ?: claimAlg
                        val target = url.substringAfterLast("c2pa.assertions/")
                        val box = assertionsBox?.childrenSuperboxes()?.firstOrNull { it.label == target }
                        if (box == null) { missing++; continue }
                        val matched = listOf(
                            store.copyOfRange(box.payloadStart, box.end),
                            store.copyOfRange(box.start, box.end),
                        ).any { digest(alg, it)?.contentEquals(expected) == true }
                        if (matched) okCount++ else badCount++
                    }
                    when {
                        refs.isEmpty() -> checks += C2paCheck("Assertion integrity", CheckResult.NOT_CHECKED, "Claim lists no assertions")
                        missing > 0 -> { checks += C2paCheck("Assertion integrity", CheckResult.FAIL, "$missing referenced assertion(s) missing"); invalid = true }
                        badCount > 0 -> { checks += C2paCheck("Assertion integrity", CheckResult.NOT_CHECKED, "$okCount matched, $badCount could not be matched (hash method not confirmed)"); unverifiable = true }
                        else -> checks += C2paCheck("Assertion integrity", CheckResult.PASS, "All $okCount assertion hashes match")
                    }

                    // ---- hard binding
                    val dataHashBox = assertionsBox?.childrenSuperboxes()?.firstOrNull { it.label?.startsWith("c2pa.hash.data") == true }
                    val otherBinding = assertionsBox?.childrenSuperboxes()?.firstOrNull {
                        it.label?.startsWith("c2pa.hash.bmff") == true || it.label?.startsWith("c2pa.hash.boxes") == true || it.label?.startsWith("c2pa.hash.collection") == true
                    }
                    if (dataHashBox != null) {
                        val r = checkDataHash(store, dataHashBox, asset, claimAlg)
                        checks += r
                        if (r.result == CheckResult.FAIL) invalid = true else if (r.result == CheckResult.NOT_CHECKED) unverifiable = true
                    } else if (otherBinding != null) {
                        checks += C2paCheck("Content binding (hard binding)", CheckResult.NOT_CHECKED, "${otherBinding.label} binding is not supported offline in this version")
                        unverifiable = true
                    } else {
                        checks += C2paCheck("Content binding (hard binding)", CheckResult.FAIL, "Manifest has no hard-binding assertion"); invalid = true
                    }
                }
            }

            manifests += C2paManifest(
                label = mb.label ?: "(unlabelled)", isActive = isActive, claimGenerator = generator,
                title = claim?.get("dc:title")?.asText(), format = claim?.get("dc:format")?.asText(),
                assertionLabels = labels, actions = actions, ingredients = ingredients, author = author,
                trainingMining = training, signerSubject = signerSubject, signerIssuer = signerIssuer,
                signingAlg = algName, claimJson = claim?.render() ?: "(claim unreadable)",
            )
        }
        if (manifestBoxes.size > 1) checks += C2paCheck("Ingredient manifests", CheckResult.NOT_CHECKED,
            "${manifestBoxes.size - 1} earlier manifest(s) shown for history; only the active manifest is validated")

        val state = when { invalid -> C2paState.INVALID; unverifiable -> C2paState.UNVERIFIABLE; else -> C2paState.VERIFIED }
        val summary = when (state) {
            C2paState.VERIFIED -> "Signature, assertions and content binding are valid and the signer chains to a trusted root."
            C2paState.INVALID -> "The credentials are present but fail validation – the file was altered after signing or the manifest is damaged."
            C2paState.UNVERIFIABLE -> "The credentials are present, but at least one check could not be completed (for example the signer is not in the trust list)."
            C2paState.NONE -> ""
        }
        return C2paReport(state, summary, manifests.reversed(), checks, aiTypes.distinct())
    }

    // ------------------------------------------------------------------ hard binding

    private fun checkDataHash(store: ByteArray, box: JumbfBox, asset: ByteArray, defaultAlg: String): C2paCheck {
        val content = box.contentBox() ?: return C2paCheck("Content binding (data hash)", CheckResult.FAIL, "Empty data-hash assertion")
        val v = runCatching { CborDecoder.decode(store.copyOfRange(content.payloadStart, content.end)) }.getOrNull()
            ?: return C2paCheck("Content binding (data hash)", CheckResult.FAIL, "Data-hash assertion unreadable")
        val alg = v["alg"]?.asText() ?: defaultAlg
        val expected = v["hash"]?.asBytes() ?: return C2paCheck("Content binding (data hash)", CheckResult.FAIL, "No hash value")
        val md = messageDigest(alg) ?: return C2paCheck("Content binding (data hash)", CheckResult.NOT_CHECKED, "Unsupported hash $alg")
        val exclusions = v["exclusions"]?.asArr().orEmpty().mapNotNull { e ->
            val s = e["start"]?.asLong(); val l = e["length"]?.asLong()
            if (s == null || l == null) null else s to l
        }.sortedBy { it.first }
        var pos = 0L
        for ((s, l) in exclusions) {
            if (s < pos || s + l > asset.size) return C2paCheck("Content binding (data hash)", CheckResult.FAIL, "Exclusion range outside file")
            md.update(asset, pos.toInt(), (s - pos).toInt())
            pos = s + l
        }
        md.update(asset, pos.toInt(), (asset.size - pos).toInt())
        val ok = md.digest().contentEquals(expected)
        return if (ok) C2paCheck("Content binding (data hash)", CheckResult.PASS, "Image bytes match the signed $alg hash")
        else C2paCheck("Content binding (data hash)", CheckResult.FAIL, "Image bytes changed after signing ($alg mismatch)")
    }

    // ------------------------------------------------------------------ COSE

    private class SigResult(val checks: List<C2paCheck>, val subject: String?, val issuer: String?, val alg: String?)

    private fun verifySignature(cose: ByteArray, claimBytes: ByteArray): SigResult? {
        val root = runCatching { CborDecoder.decode(cose) }.getOrNull() ?: return null
        val arr = root.asArr() ?: return null
        if (arr.size != 4) return null
        val protectedBytes = arr[0].asBytes() ?: return null
        val protectedHdr = if (protectedBytes.isEmpty()) null else runCatching { CborDecoder.decode(protectedBytes) }.getOrNull()
        val unprotected = arr[1]
        val signature = arr[3].asBytes() ?: return null
        val algId = protectedHdr?.get(1L)?.asLong() ?: unprotected[1L]?.asLong()
        val x5 = protectedHdr?.get(33L) ?: unprotected[33L] ?: protectedHdr?.get("x5chain") ?: unprotected["x5chain"]
        val certDers = when {
            x5 == null -> emptyList()
            x5.asBytes() != null -> listOf(x5.asBytes()!!)
            else -> x5.asArr().orEmpty().mapNotNull { it.asBytes() }
        }
        val checks = ArrayList<C2paCheck>()
        val cf = CertificateFactory.getInstance("X.509")
        val chain = certDers.mapNotNull { runCatching { cf.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }.getOrNull() }
        if (chain.isEmpty()) {
            checks += C2paCheck("Claim signature", CheckResult.FAIL, "No signing certificate (x5chain) in signature")
            return SigResult(checks, null, null, algName(algId))
        }
        val leaf = chain.first()
        val payload = arr[2].asBytes() ?: claimBytes // detached payload = claim
        val toBeSigned = CborEncoder.encode(
            CborValue.Arr(listOf(CborValue.Text("Signature1"), CborValue.Bytes(protectedBytes), CborValue.Bytes(ByteArray(0)), CborValue.Bytes(payload)))
        )
        val sigOk = runCatching { verifyRaw(algId, leaf.publicKey, toBeSigned, signature) }
        checks += when {
            sigOk.isFailure -> C2paCheck("Claim signature", CheckResult.NOT_CHECKED, "Algorithm ${algName(algId)} unsupported on this device: ${sigOk.exceptionOrNull()?.message}")
            sigOk.getOrNull() == true -> C2paCheck("Claim signature", CheckResult.PASS, "${algName(algId)} signature over the claim is valid")
            else -> C2paCheck("Claim signature", CheckResult.FAIL, "Signature does not match the claim – manifest was tampered with")
        }
        // chain integrity
        var chainOk = true
        for (k in 0 until chain.size - 1) {
            if (runCatching { chain[k].verify(chain[k + 1].publicKey) }.isFailure) chainOk = false
        }
        checks += C2paCheck("Certificate chain integrity", if (chainOk) CheckResult.PASS else CheckResult.FAIL,
            if (chainOk) "${chain.size} certificate(s), each signed by its issuer" else "A certificate in the chain is not signed by the next one")
        val now = Date()
        val expired = chain.filter { runCatching { it.checkValidity(now) }.isFailure }
        checks += C2paCheck("Certificate validity", if (expired.isEmpty()) CheckResult.PASS else CheckResult.NOT_CHECKED,
            if (expired.isEmpty()) "Certificates are within their validity period"
            else "Certificate(s) outside validity period today; a signing-time timestamp may still make them valid (timestamps not validated offline)")
        val trustReason = runCatching { trust.evaluate(chain) }.getOrElse { it.message ?: "trust evaluation error" }
        checks += C2paCheck("Signer trust", if (trustReason == null) CheckResult.PASS else CheckResult.NOT_CHECKED,
            trustReason?.let { "Signer not anchored in the device or imported trust list: $it" } ?: "Signer chains to a trusted root certificate")
        return SigResult(checks, leaf.subjectX500Principal.name, leaf.issuerX500Principal.name, algName(algId))
    }

    private fun algName(id: Long?) = when (id) {
        -7L -> "ES256"; -35L -> "ES384"; -36L -> "ES512"; -37L -> "PS256"; -38L -> "PS384"; -39L -> "PS512"; -8L -> "Ed25519"
        else -> "alg($id)"
    }

    private fun verifyRaw(alg: Long?, key: PublicKey, data: ByteArray, sig: ByteArray): Boolean = when (alg) {
        -7L, -35L, -36L -> {
            val name = when (alg) { -7L -> "SHA256withECDSA"; -35L -> "SHA384withECDSA"; else -> "SHA512withECDSA" }
            Signature.getInstance(name).run { initVerify(key); update(data); verify(rawEcdsaToDer(sig)) }
        }
        -37L, -38L, -39L -> {
            val (h, len) = when (alg) { -37L -> "SHA-256" to 32; -38L -> "SHA-384" to 48; else -> "SHA-512" to 64 }
            val mgf = when (alg) { -37L -> MGF1ParameterSpec.SHA256; -38L -> MGF1ParameterSpec.SHA384; else -> MGF1ParameterSpec.SHA512 }
            val s = runCatching { Signature.getInstance(h.replace("-", "") + "withRSA/PSS") }.getOrNull()
                ?: Signature.getInstance("RSASSA-PSS")
            if (s.algorithm == "RSASSA-PSS") s.setParameter(PSSParameterSpec(h, "MGF1", mgf, len, 1))
            s.initVerify(key); s.update(data); s.verify(sig)
        }
        -8L -> Signature.getInstance("Ed25519").run { initVerify(key); update(data); verify(sig) }
        else -> throw IllegalArgumentException("unknown COSE algorithm")
    }

    /** COSE ECDSA signatures are raw r||s; Java expects ASN.1 DER SEQUENCE{INTEGER r, INTEGER s}. */
    private fun rawEcdsaToDer(raw: ByteArray): ByteArray {
        val n = raw.size / 2
        fun enc(x: ByteArray): ByteArray { val bi = BigInteger(1, x).toByteArray(); return byteArrayOf(0x02, bi.size.toByte()) + bi }
        val body = enc(raw.copyOfRange(0, n)) + enc(raw.copyOfRange(n, raw.size))
        val lenBytes = if (body.size < 128) byteArrayOf(body.size.toByte()) else byteArrayOf(0x81.toByte(), body.size.toByte())
        return byteArrayOf(0x30) + lenBytes + body
    }

    companion object {
        fun messageDigest(alg: String): MessageDigest? = when (alg.lowercase()) {
            "sha256" -> MessageDigest.getInstance("SHA-256"); "sha384" -> MessageDigest.getInstance("SHA-384")
            "sha512" -> MessageDigest.getInstance("SHA-512"); else -> null
        }
        fun digest(alg: String, data: ByteArray): ByteArray? = messageDigest(alg)?.digest(data)
        @Suppress("unused") fun hex(b: ByteArray) = Bytes.hex(b)
    }
}
