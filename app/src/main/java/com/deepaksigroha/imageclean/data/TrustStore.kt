package com.deepaksigroha.imageclean.data

import android.content.Context
import com.deepaksigroha.imageclean.core.c2pa.TrustEvaluator
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * Offline trust evaluation for C2PA signers.
 * Anchors = Android system CA store + certificates the user imported in Settings (e.g. the
 * published C2PA trust list roots). No network access is used; revocation is not checked.
 */
class TrustStore(private val ctx: Context) : TrustEvaluator {

    private val dir get() = File(ctx.filesDir, "trust").apply { mkdirs() }

    fun userAnchors(): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return dir.listFiles().orEmpty().flatMap { f ->
            runCatching { cf.generateCertificates(ByteArrayInputStream(f.readBytes())).filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())
        }
    }

    /** Imports PEM or DER certificate(s); returns how many were added. */
    fun import(bytes: ByteArray): Int {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = cf.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        certs.forEach { c -> File(dir, "${c.serialNumber.toString(16)}_${c.hashCode()}.der").writeBytes(c.encoded) }
        return certs.size
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun systemAnchors(): Set<TrustAnchor> = runCatching {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        ks.aliases().toList().mapNotNull { (ks.getCertificate(it) as? X509Certificate)?.let { c -> TrustAnchor(c, null) } }.toSet()
    }.getOrDefault(emptySet())

    override fun evaluate(chain: List<X509Certificate>): String? {
        val anchors = systemAnchors() + userAnchors().map { TrustAnchor(it, null) }
        if (anchors.isEmpty()) return "no trust anchors available"
        // If the chain itself contains an anchor, that is acceptable too.
        if (chain.any { c -> anchors.any { it.trustedCert == c } }) return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val path = cf.generateCertPath(chain)
            val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
            CertPathValidator.getInstance("PKIX").validate(path, params)
            null
        } catch (e: Exception) {
            e.message?.take(160) ?: "certificate path could not be validated"
        }
    }
}
