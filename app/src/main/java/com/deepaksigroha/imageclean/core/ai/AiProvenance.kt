package com.deepaksigroha.imageclean.core.ai

import com.deepaksigroha.imageclean.core.c2pa.C2paReport
import com.deepaksigroha.imageclean.core.c2pa.C2paState
import com.deepaksigroha.imageclean.core.meta.AiPatterns
import com.deepaksigroha.imageclean.core.model.ContainerInfo
import com.deepaksigroha.imageclean.core.model.IptcData
import com.deepaksigroha.imageclean.core.model.XmpData

enum class EvidenceKind(val label: String) {
    CRYPTOGRAPHIC("Cryptographically verified (C2PA)"),
    SIGNED_UNVERIFIED("Signed manifest – not fully verified"),
    METADATA("Metadata-based (not tamper-proof)"),
}

enum class EvidenceStrength { STRONG, MODERATE, WEAK }

data class AiEvidence(
    val source: String,     // e.g. "C2PA action", "XMP Iptc4xmpExt:DigitalSourceType"
    val finding: String,
    val kind: EvidenceKind,
    val strength: EvidenceStrength,
    val generated: Boolean, // true = fully AI-generated; false = AI-assisted editing
)

enum class AiVerdict(val title: String) {
    AI_GENERATED("AI-generated provenance detected"),
    AI_ASSISTED("AI-assisted editing detected"),
    C2PA_VERIFIED_NO_AI("C2PA credentials verified – no AI provenance declared"),
    C2PA_INVALID("C2PA credentials invalid"),
    NONE("No detectable AI provenance"),
    UNDETERMINED("Unable to determine"),
}

data class AiScanResult(
    val verdict: AiVerdict,
    val explanation: String,
    val evidence: List<AiEvidence>,
    val c2paState: C2paState,
    val strongestSourceType: String?,
) {
    val cryptographic get() = evidence.any { it.kind == EvidenceKind.CRYPTOGRAPHIC }
}

/** Inputs from EXIF that matter for provenance (gathered by the Android layer). */
data class ExifProvenance(
    val software: String?,
    val make: String?,
    val model: String?,
    val description: String?,
    val userComment: String?,
    val artist: String?,
    val hasAnyExif: Boolean,
)

object AiProvenanceScanner {

    const val DISCLAIMER = "The absence of AI metadata does not prove an image is human-made: metadata is easily removed, " +
        "and platforms may use their own detectors (such as invisible watermarks) that this app cannot read."

    fun scan(
        container: ContainerInfo,
        exif: ExifProvenance,
        iptc: IptcData?,
        xmp: XmpData?,
        c2pa: C2paReport,
    ): AiScanResult {
        val ev = ArrayList<AiEvidence>()

        // 1. C2PA
        if (c2pa.state != C2paState.NONE) {
            val kind = if (c2pa.state == C2paState.VERIFIED) EvidenceKind.CRYPTOGRAPHIC else EvidenceKind.SIGNED_UNVERIFIED
            c2pa.manifests.forEach { m ->
                m.actions.forEach { a ->
                    val st = a.digitalSourceType?.let { AiPatterns.matchSourceType(it) }
                    if (st != null && st.cls in setOf(AiPatterns.SourceTypeClass.AI_GENERATED, AiPatterns.SourceTypeClass.AI_ASSISTED)) {
                        val activeKind = if (m.isActive) kind else EvidenceKind.SIGNED_UNVERIFIED
                        ev += AiEvidence("C2PA action ${a.action}" + (a.softwareAgent?.let { " by $it" } ?: ""),
                            st.label, activeKind, EvidenceStrength.STRONG, st.cls == AiPatterns.SourceTypeClass.AI_GENERATED)
                    }
                    AiPatterns.matchGenerator(a.softwareAgent)?.let { g ->
                        if (st == null) ev += AiEvidence("C2PA action ${a.action}", "Software agent is $g", EvidenceKind.SIGNED_UNVERIFIED,
                            EvidenceStrength.MODERATE, a.action == "c2pa.created")
                    }
                }
                AiPatterns.matchGenerator(m.claimGenerator)?.let { g ->
                    ev += AiEvidence("C2PA claim generator", "Manifest written by $g (${m.claimGenerator})", EvidenceKind.SIGNED_UNVERIFIED,
                        EvidenceStrength.MODERATE, true)
                }
            }
        }

        // 2. XMP
        xmp?.let { x ->
            x.digitalSourceType?.let { AiPatterns.matchSourceType(it) }?.let { st ->
                if (st.cls == AiPatterns.SourceTypeClass.AI_GENERATED || st.cls == AiPatterns.SourceTypeClass.AI_ASSISTED)
                    ev += AiEvidence("XMP Iptc4xmpExt:DigitalSourceType", st.label, EvidenceKind.METADATA, EvidenceStrength.STRONG,
                        st.cls == AiPatterns.SourceTypeClass.AI_GENERATED)
            }
            AiPatterns.matchGenerator(x.creatorTool)?.let { g ->
                ev += AiEvidence("XMP xmp:CreatorTool", "Created with $g (${x.creatorTool})", EvidenceKind.METADATA, EvidenceStrength.MODERATE, isGeneratorFull(g))
            }
            x.history.forEach { h -> AiPatterns.matchGenerator(h)?.let { g ->
                ev += AiEvidence("XMP editing history", "History entry mentions $g: ${h.take(80)}", EvidenceKind.METADATA, EvidenceStrength.MODERATE, false)
            } }
            x.properties.forEach { (k, v) ->
                val lk = k.lowercase()
                if (AiPatterns.isAiPropertyKey(lk)) ev += AiEvidence("XMP $k", v.take(120), EvidenceKind.METADATA, EvidenceStrength.MODERATE, true)
            }
        }

        // 3. IPTC IIM
        iptc?.let { i ->
            i.raw.values.forEach { v -> AiPatterns.matchSourceType(v)?.let { st ->
                if (st.cls == AiPatterns.SourceTypeClass.AI_GENERATED || st.cls == AiPatterns.SourceTypeClass.AI_ASSISTED)
                    ev += AiEvidence("IPTC IIM", st.label, EvidenceKind.METADATA, EvidenceStrength.STRONG, st.cls == AiPatterns.SourceTypeClass.AI_GENERATED)
            } }
            (listOfNotNull(i.description, i.credit, i.source, i.headline) + i.keywords).forEach { t ->
                AiPatterns.matchGenerator(t)?.let { g -> ev += AiEvidence("IPTC text", "Mentions $g", EvidenceKind.METADATA, EvidenceStrength.WEAK, isGeneratorFull(g)) }
            }
        }

        // 4. EXIF
        AiPatterns.matchGenerator(exif.software)?.let { g ->
            ev += AiEvidence("EXIF Software", "Software is $g (${exif.software})", EvidenceKind.METADATA, EvidenceStrength.MODERATE, isGeneratorFull(g))
        }
        listOfNotNull(exif.description, exif.userComment, exif.artist, exif.make, exif.model).forEach { t ->
            AiPatterns.matchGenerator(t)?.let { g -> ev += AiEvidence("EXIF text field", "Mentions $g", EvidenceKind.METADATA, EvidenceStrength.WEAK, isGeneratorFull(g)) }
        }

        // 5. PNG text chunks (local diffusion tools)
        container.textChunks.forEach { (k, v) ->
            val known = AiPatterns.aiPngTextKeys[k]
            if (!known.isNullOrEmpty() && looksLikeGenerationParams(k, v))
                ev += AiEvidence("PNG text '$k'", known, EvidenceKind.METADATA, EvidenceStrength.STRONG, true)
            else AiPatterns.matchGenerator(v)?.let { g -> ev += AiEvidence("PNG text '$k'", "Mentions $g", EvidenceKind.METADATA, EvidenceStrength.MODERATE, isGeneratorFull(g)) }
        }
        container.comments.forEach { c -> AiPatterns.matchGenerator(c)?.let { g ->
            ev += AiEvidence("JPEG comment", "Mentions $g", EvidenceKind.METADATA, EvidenceStrength.WEAK, isGeneratorFull(g))
        } }

        val unique = ev.distinctBy { it.source + it.finding }
        val strongest = unique.firstOrNull { it.strength == EvidenceStrength.STRONG }?.finding

        val hasGenerated = unique.any { it.generated && it.strength != EvidenceStrength.WEAK }
        val hasAssisted = unique.any { !it.generated && it.strength != EvidenceStrength.WEAK }
        val hasWeakOnly = unique.isNotEmpty() && !hasGenerated && !hasAssisted

        val noMetadata = !exif.hasAnyExif && !container.hasXmp && !container.hasIptc && !container.hasC2pa && container.textChunks.isEmpty()

        val verdict = when {
            hasGenerated -> AiVerdict.AI_GENERATED
            hasAssisted -> AiVerdict.AI_ASSISTED
            c2pa.state == C2paState.INVALID -> AiVerdict.C2PA_INVALID
            c2pa.state == C2paState.VERIFIED -> AiVerdict.C2PA_VERIFIED_NO_AI
            hasWeakOnly -> AiVerdict.UNDETERMINED
            noMetadata -> AiVerdict.UNDETERMINED
            else -> AiVerdict.NONE
        }
        val basis = when {
            unique.any { it.kind == EvidenceKind.CRYPTOGRAPHIC } -> "The finding is backed by a cryptographically verified C2PA manifest."
            unique.any { it.kind == EvidenceKind.SIGNED_UNVERIFIED } -> "The finding comes from a signed C2PA manifest that could not be fully verified."
            unique.isNotEmpty() -> "The finding is based on ordinary metadata, which anyone can add, edit or remove."
            else -> ""
        }
        val explanation = when (verdict) {
            AiVerdict.AI_GENERATED -> "The file declares that it was created with generative AI. $basis"
            AiVerdict.AI_ASSISTED -> "The file declares AI-assisted editing or AI-generated elements. $basis"
            AiVerdict.C2PA_INVALID -> "Content Credentials exist but failed validation, so their claims cannot be relied on. No other AI metadata was found."
            AiVerdict.C2PA_VERIFIED_NO_AI -> "Valid Content Credentials are present and none of their actions declare generative AI."
            AiVerdict.NONE -> "Metadata is present but contains no AI-generation markers. ${DISCLAIMER}"
            AiVerdict.UNDETERMINED -> if (noMetadata) "The file carries no EXIF, XMP, IPTC or C2PA metadata, so its origin cannot be determined from metadata. $DISCLAIMER"
                else "Only weak hints were found (for example a tool name in a free-text field). $DISCLAIMER"
        }
        return AiScanResult(verdict, explanation, unique, c2pa.state, strongest)
    }

    private fun isGeneratorFull(name: String) = name !in setOf("Adobe Generative Fill/Expand", "Samsung Generative Edit", "Google Magic Editor")

    private fun looksLikeGenerationParams(key: String, v: String): Boolean = when (key) {
        "parameters" -> v.contains("Steps:", true) || v.contains("Sampler", true) || v.contains("Negative prompt", true) || v.contains("CFG", true)
        "prompt", "workflow" -> v.trimStart().startsWith("{") && (v.contains("class_type") || v.contains("nodes"))
        "Comment" -> v.contains("\"prompt\"") && v.contains("steps")
        else -> v.length > 20
    }
}
