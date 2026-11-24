package com.deepaksigroha.imageclean.core.ai

import com.deepaksigroha.imageclean.core.c2pa.C2paState
import com.deepaksigroha.imageclean.core.model.ImageFormat

enum class IgAssessment(val title: String, val description: String) {
    LOW("Low detected metadata indicators",
        "No AI provenance markers that platforms are known to read were found in the file's metadata."),
    SOME("Some AI provenance indicators detected",
        "The file contains hints of AI involvement that a platform might read, but no strong standard disclosure."),
    STRONG("Strong AI provenance indicators detected",
        "The file contains standard AI disclosures (C2PA and/or IPTC DigitalSourceType) that Meta has publicly said it reads."),
}

data class IgInput(
    val ai: AiScanResult,
    val format: ImageFormat,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val hasGps: Boolean,
    val hasDeviceInfo: Boolean,
    val hasDateTime: Boolean,
    val hasXmp: Boolean,
    val hasIptc: Boolean,
    val hasPngText: Boolean,
    val iccDescription: String?,
    val visibleAiMarks: List<String>,
)

data class IgReport(
    val assessment: IgAssessment,
    val aiIndicators: List<String>,
    val disclosureIndicators: List<String>,
    val privacyConcerns: List<String>,
    val formatNotes: List<String>,
    val recommendations: List<String>,
    val disclaimer: String,
)

/**
 * Explains *why* Instagram might show an "AI info" label. It is an explanation of what is
 * in the file – never a prediction or guarantee of how Instagram will classify the image.
 */
object InstagramChecker {

    const val DISCLAIMER = "This is not a prediction of Instagram's decision. Meta can label images using its own " +
        "classifiers, invisible watermarks (e.g. from Meta AI, Google SynthID), user self-disclosure and signals that are " +
        "not visible in file metadata. Removing metadata does not guarantee that no label is shown, and hiding the " +
        "origin of AI-generated content may breach platform rules."

    fun check(i: IgInput): IgReport {
        val ai = ArrayList<String>()
        val disclosure = ArrayList<String>()
        var strong = 0
        var some = 0

        if (i.ai.c2paState != C2paState.NONE) {
            disclosure += "C2PA Content Credentials present (${i.ai.c2paState.title.lowercase()})."
            if (i.ai.evidence.any { it.source.startsWith("C2PA") }) { strong++; ai += "C2PA manifest declares AI involvement." }
        }
        i.ai.evidence.forEach { e ->
            val line = "${e.source}: ${e.finding}"
            when {
                e.source.startsWith("C2PA") -> {}
                e.strength == EvidenceStrength.STRONG && (e.source.contains("DigitalSourceType") || e.source.startsWith("IPTC")) -> {
                    strong++; ai += line; disclosure += "IPTC Digital Source Type disclosure (read by Meta, Google and others)."
                }
                e.strength == EvidenceStrength.WEAK -> { ai += "$line (weak hint)" }
                else -> { some++; ai += line }
            }
        }
        if (i.visibleAiMarks.isNotEmpty()) { some++; i.visibleAiMarks.forEach { ai += "Visible mark: $it" } }

        val assessment = when {
            strong > 0 -> IgAssessment.STRONG
            some > 0 -> IgAssessment.SOME
            else -> IgAssessment.LOW
        }

        val privacy = ArrayList<String>()
        if (i.hasGps) privacy += "GPS location is embedded (Instagram strips it on upload, but other apps and direct file sharing may not)."
        if (i.hasDeviceInfo) privacy += "Camera/phone make and model are embedded."
        if (i.hasDateTime) privacy += "Original capture date and time are embedded."

        val fmt = ArrayList<String>()
        when (i.format) {
            ImageFormat.HEIF, ImageFormat.AVIF -> fmt += "${i.format.label} is converted to JPEG by Instagram; C2PA data in this container may not survive."
            ImageFormat.PNG -> {
                fmt += "PNG is recompressed to JPEG by Instagram."
                if (i.hasPngText) fmt += "PNG text chunks (e.g. Stable Diffusion parameters) are not a standard disclosure format."
            }
            ImageFormat.WEBP -> fmt += "WebP is converted to JPEG on upload."
            ImageFormat.UNKNOWN -> fmt += "Unrecognised format – may be rejected by Instagram."
            else -> {}
        }
        if (i.fileSize > 8L * 1024 * 1024) fmt += "File is larger than 8 MB; Instagram will recompress heavily."
        if (i.width > 1440 || i.height > 1800) fmt += "Resolution exceeds Instagram's 1080 px display width; it will be downscaled."
        val ratio = if (i.height > 0) i.width.toDouble() / i.height else 1.0
        if (ratio < 0.8 - 0.01 || ratio > 1.91 + 0.01) fmt += "Aspect ratio %.2f is outside Instagram feed limits (4:5 to 1.91:1) and will be cropped.".format(ratio)
        i.iccDescription?.let { if (!it.contains("sRGB", true)) fmt += "Colour profile '$it' is not sRGB; colours may shift after upload." }

        val rec = listOf(
            "Export as JPEG, sRGB, quality 85–92.",
            "Feed: 1080 × 1350 (4:5) or 1080 × 1080 (1:1). Stories/Reels: 1080 × 1920 (9:16).",
            "Remove private metadata (GPS, device, timestamps) with the Metadata Cleaner – the app keeps AI disclosures intact.",
            "If the image was made or substantially edited with AI, use Instagram's own 'AI label' toggle to disclose it.",
        )
        return IgReport(assessment, ai.distinct(), disclosure.distinct(), privacy, fmt, rec, DISCLAIMER)
    }
}
