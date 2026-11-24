package com.deepaksigroha.imageclean.core.model

enum class ImageFormat(val label: String, val mime: String) {
    JPEG("JPEG", "image/jpeg"),
    PNG("PNG", "image/png"),
    WEBP("WebP", "image/webp"),
    HEIF("HEIC/HEIF", "image/heif"),
    AVIF("AVIF", "image/avif"),
    UNKNOWN("Unknown", "application/octet-stream");

    companion object {
        fun detect(b: ByteArray): ImageFormat {
            if (b.size < 12) return UNKNOWN
            if ((b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8) return JPEG
            if ((b[0].toInt() and 0xFF) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) return PNG
            val riff = String(b, 0, 4, Charsets.ISO_8859_1)
            val webp = String(b, 8, 4, Charsets.ISO_8859_1)
            if (riff == "RIFF" && webp == "WEBP") return WEBP
            val ftyp = String(b, 4, 4, Charsets.ISO_8859_1)
            if (ftyp == "ftyp") {
                val brand = String(b, 8, 4, Charsets.ISO_8859_1)
                return when {
                    brand.startsWith("avi") -> AVIF
                    brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") -> HEIF
                    else -> HEIF
                }
            }
            return UNKNOWN
        }
    }
}

/** A metadata block found in the file container. */
data class MetadataBlock(
    val kind: BlockKind,
    val offset: Int,
    val length: Int,
    val note: String = "",
)

enum class BlockKind(val label: String) {
    EXIF("EXIF"),
    XMP("XMP"),
    XMP_EXTENDED("Extended XMP"),
    IPTC("IPTC (Photoshop IRB)"),
    ICC("ICC colour profile"),
    C2PA("C2PA / JUMBF manifest"),
    COMMENT("Comment"),
    TEXT("Text chunk"),
    OTHER_APP("Other application segment"),
}

/** Low-level container scan results (format independent). */
data class ContainerInfo(
    val format: ImageFormat,
    val blocks: List<MetadataBlock>,
    val xmpPackets: List<String>,
    val iptcIim: ByteArray?,
    val c2paJumbf: ByteArray?,
    val iccProfileDescription: String?,
    val comments: List<String>,
    val textChunks: Map<String, String>,
    val warnings: List<String>,
) {
    val hasExif get() = blocks.any { it.kind == BlockKind.EXIF }
    val hasXmp get() = xmpPackets.isNotEmpty()
    val hasIptc get() = iptcIim != null
    val hasC2pa get() = c2paJumbf != null
    val hasIcc get() = blocks.any { it.kind == BlockKind.ICC }
}

data class IptcData(
    val creator: List<String> = emptyList(),
    val copyright: String? = null,
    val description: String? = null,
    val keywords: List<String> = emptyList(),
    val headline: String? = null,
    val credit: String? = null,
    val source: String? = null,
    val city: String? = null,
    val country: String? = null,
    val raw: Map<String, String> = emptyMap(),
)

data class XmpData(
    val properties: Map<String, String>,
    val creatorTool: String?,
    val digitalSourceType: String?,
    val creators: List<String>,
    val rights: String?,
    val description: String?,
    val history: List<String>,
    val aiHints: List<String>,
    val provenanceRefs: List<String>,
)

/** Display-ready EXIF row. */
data class ExifField(val tag: String, val label: String, val value: String, val category: ExifCategory)

enum class ExifCategory(val label: String, val privacySensitive: Boolean) {
    LOCATION("Location (GPS)", true),
    DEVICE("Device & camera", true),
    DATETIME("Date & time", true),
    SOFTWARE("Software", true),
    PERSONAL("Personal / comments", true),
    CAPTURE("Capture settings", false),
    IMAGE("Image structure", false),
    THUMBNAIL("Embedded thumbnail", true),
    OTHER("Other", false),
}
