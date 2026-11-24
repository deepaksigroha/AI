package com.deepaksigroha.imageclean.data

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.deepaksigroha.imageclean.core.ai.AiVerdict
import com.deepaksigroha.imageclean.core.clean.JpegRewriter
import com.deepaksigroha.imageclean.core.clean.PngRewriter
import com.deepaksigroha.imageclean.core.clean.XmpBuilder
import com.deepaksigroha.imageclean.core.meta.AiPatterns
import com.deepaksigroha.imageclean.core.model.ImageFormat
import java.io.ByteArrayOutputStream
import java.io.File

data class CleanOptions(
    val removeTags: Set<String> = emptySet(),
    val removeThumbnail: Boolean = false,
    val removeXmp: Boolean = false,
    val removeIptc: Boolean = false,
    val removeComments: Boolean = false,
    val removePngText: Boolean = false,
)

data class CleanResult(
    val bytes: ByteArray,
    val format: ImageFormat,
    val removed: List<String>,
    val retained: List<String>,
    val modified: List<String>,
    val warnings: List<String>,
    val reencoded: Boolean,
)

/**
 * Produces a cleaned COPY of an image. The original is never touched.
 * JPEG/PNG: lossless (header-only) edits. WebP: EXIF tag edits only. HEIF/AVIF: re-encoded to JPEG.
 * AI disclosures (IPTC DigitalSourceType) and C2PA manifests are always preserved.
 */
class MetadataCleaner(private val ctx: Context) {

    companion object {
        /** All tags that the app classifies as privacy-sensitive. */
        val SENSITIVE: Set<String> = ExifReader.TAGS.filter { it.third.privacySensitive }.map { it.first }.toSet()
    }

    fun clean(a: ImageAnalysis, o: CleanOptions): CleanResult {
        val removed = ArrayList<String>(); val retained = ArrayList<String>(); val modified = ArrayList<String>()
        val warnings = ArrayList<String>()
        val aiDst = aiDisclosure(a)
        val tags = o.removeTags - ExifReader.STRUCTURAL
        if (o.removeTags.any { it in ExifReader.STRUCTURAL }) warnings += "Structural tags (orientation, dimensions, colour space) were kept so the image displays correctly."

        if (a.format == ImageFormat.HEIF || a.format == ImageFormat.AVIF || a.format == ImageFormat.UNKNOWN) {
            return reencode(a, o, tags, aiDst)
        }

        val dir = File(ctx.cacheDir, "work").apply { mkdirs() }
        val tmp = File.createTempFile("clean", "." + a.format.name.lowercase(), dir)
        try {
            tmp.writeBytes(a.image.bytes)

            // ---- 1. EXIF tag-level removal
            val present = a.exif.fields.map { it.tag }.toSet()
            val toRemove = tags.filter { it in present && it != "__thumbnail" }
            if (toRemove.isNotEmpty()) {
                val ex = ExifInterface(tmp)
                toRemove.forEach { ex.setAttribute(it, null) }
                ex.saveAttributes()
                toRemove.forEach { t -> removed += "EXIF ${label(t)}" }
            }

            // ---- 2. Thumbnail (rebuild EXIF without IFD1)
            if (o.removeThumbnail && a.exif.hasThumbnail) {
                if (a.format == ImageFormat.JPEG || a.format == ImageFormat.PNG) {
                    rebuildExifWithoutThumbnail(tmp, a.format)
                    removed += "EXIF embedded thumbnail"
                    if (present.contains("MakerNote") && "MakerNote" !in toRemove) { removed += "EXIF maker note (cannot be rewritten safely)" }
                    modified += "EXIF block rebuilt (remaining tags rewritten)"
                } else warnings += "Thumbnail removal is not supported for ${a.format.label}."
            }

            var bytes = tmp.readBytes()

            // ---- 3. Container-level blocks
            val removingSomething = o.removeXmp || o.removeIptc || o.removePngText
            val xmpHasAiDst = a.xmp?.digitalSourceType?.let { AiPatterns.matchSourceType(it) }?.cls in
                setOf(AiPatterns.SourceTypeClass.AI_GENERATED, AiPatterns.SourceTypeClass.AI_ASSISTED)
            val disclosure: String? = when {
                aiDst == null || !removingSomething -> null
                o.removeXmp || !a.container.hasXmp -> XmpBuilder.disclosurePacket(aiDst, note = "AI disclosure retained by ImageClean Pro")
                xmpHasAiDst -> null
                else -> XmpBuilder.mergeDisclosure(a.container.xmpPackets.first(), aiDst)
            }
            when (a.format) {
                ImageFormat.JPEG -> {
                    val drop = HashSet<JpegRewriter.Seg>()
                    if (o.removeXmp && a.container.hasXmp) { drop += JpegRewriter.Seg.XMP; drop += JpegRewriter.Seg.XMP_EXT; removed += "XMP packet (editing history, creator tool, descriptions)" }
                    if (o.removeIptc && a.container.hasIptc) { drop += JpegRewriter.Seg.IPTC_IRB; removed += "IPTC / Photoshop resource block" }
                    if (o.removeComments && a.container.comments.isNotEmpty()) { drop += JpegRewriter.Seg.COMMENT; removed += "JPEG comment(s)" }
                    if (drop.isNotEmpty() || disclosure != null) bytes = JpegRewriter.removeSegments(bytes, drop, disclosure)
                }
                ImageFormat.PNG -> {
                    val dropText = o.removePngText && a.container.textChunks.isNotEmpty()
                    val dropXmp = o.removeXmp && a.container.hasXmp
                    if (dropText) removed += "PNG text chunks (${a.container.textChunks.keys.joinToString()})"
                    if (dropXmp) removed += "XMP packet"
                    if (dropText || dropXmp || disclosure != null) {
                        bytes = PngRewriter.rewrite(bytes, { c, b ->
                            val isXmp = c.type == "iTXt" && String(b, c.dataStart, minOf(17, c.length), Charsets.ISO_8859_1) == "XML:com.adobe.xmp"
                            (dropXmp && isXmp) || (dropText && !isXmp && c.type in setOf("tEXt", "zTXt", "iTXt"))
                        }, disclosure)
                    }
                }
                ImageFormat.WEBP -> {
                    if (o.removeXmp && a.container.hasXmp) warnings += "XMP removal for WebP is not supported in this version; XMP was kept."
                }
                else -> {}
            }
            if (disclosure != null) {
                retained += "AI disclosure: IPTC DigitalSourceType = ${AiPatterns.matchSourceType(aiDst!!)?.code ?: aiDst}"
                modified += "XMP written/updated so the AI disclosure (IPTC DigitalSourceType) is preserved"
            }
            if (a.container.hasC2pa) {
                retained += "C2PA Content Credentials (never modified or removed)"
                if (bytes.size != a.image.bytes.size || !bytes.contentEquals(a.image.bytes))
                    warnings += "The file bytes changed, so the C2PA content-binding hash will no longer match. The manifest and its AI/provenance claims are kept, but verifiers will report the copy as modified."
            }
            if (a.container.hasIcc) retained += "ICC colour profile"
            retained += "Image pixels (no recompression)"
            val keptExif = a.exif.fields.map { it.tag }.filter { it !in toRemove && it != "__thumbnail" }
            if (keptExif.isNotEmpty()) retained += "EXIF: " + keptExif.joinToString { label(it) }
            if (removed.isEmpty()) warnings += "Nothing was selected that exists in this file – the copy is identical in content."
            return CleanResult(bytes, a.format, removed, retained, modified, warnings, reencoded = false)
        } finally {
            tmp.delete()
        }
    }

    private fun rebuildExifWithoutThumbnail(file: File, format: ImageFormat) {
        val old = ExifInterface(file)
        val keep = ExifReader.TAGS.map { it.first }.filter { it != "MakerNote" }
            .mapNotNull { t -> old.getAttribute(t)?.let { t to it } }
        val bytes = file.readBytes()
        val stripped = when (format) {
            ImageFormat.JPEG -> JpegRewriter.removeSegments(bytes, setOf(JpegRewriter.Seg.EXIF))
            ImageFormat.PNG -> PngRewriter.rewrite(bytes, { c, _ -> c.type == "eXIf" })
            else -> bytes
        }
        file.writeBytes(stripped)
        if (keep.isNotEmpty()) {
            val ex = ExifInterface(file)
            keep.forEach { (t, v) -> runCatching { ex.setAttribute(t, v) } }
            ex.saveAttributes()
        }
    }

    /** HEIF/AVIF cannot be edited in place on Android – decode and write a high-quality JPEG copy. */
    private fun reencode(a: ImageAnalysis, o: CleanOptions, tags: Set<String>, aiDst: String?): CleanResult {
        val bmp = ImageIO.decode(a.image.bytes, 0)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        val dir = File(ctx.cacheDir, "work").apply { mkdirs() }
        val tmp = File.createTempFile("reenc", ".jpg", dir)
        try {
            tmp.writeBytes(out.toByteArray())
            val keep = a.exif.fields.filter { it.tag !in tags && it.tag !in ExifReader.STRUCTURAL && it.tag != "__thumbnail" && it.tag != "MakerNote" }
            val src = ExifInterface(java.io.ByteArrayInputStream(a.image.bytes))
            if (keep.isNotEmpty()) {
                val ex = ExifInterface(tmp)
                keep.forEach { f -> src.getAttribute(f.tag)?.let { runCatching { ex.setAttribute(f.tag, it) } } }
                ex.saveAttributes()
            }
            var bytes = tmp.readBytes()
            val retained = ArrayList<String>()
            if (aiDst != null) {
                bytes = JpegRewriter.removeSegments(bytes, emptySet(), XmpBuilder.disclosurePacket(aiDst, note = "AI disclosure retained by ImageClean Pro"))
                retained += "AI disclosure: IPTC DigitalSourceType carried into XMP"
            }
            if (keep.isNotEmpty()) retained += "EXIF: " + keep.joinToString { it.label }
            val removed = a.exif.fields.filter { it.tag in tags || it.tag == "__thumbnail" || it.tag == "MakerNote" }.map { "EXIF ${it.label}" } +
                listOfNotNull("XMP".takeIf { a.container.hasXmp && aiDst == null }, "Container-level metadata items")
            val warnings = arrayListOf("${a.format.label} was re-encoded to JPEG (quality 95) because Android cannot rewrite its metadata in place.")
            if (a.container.hasC2pa) warnings += "C2PA Content Credentials cannot be carried into a re-encoded copy. Any AI disclosure was preserved as IPTC metadata instead."
            return CleanResult(bytes, ImageFormat.JPEG, removed, retained, listOf("Pixels re-encoded as JPEG q95, colour converted to sRGB"), warnings, reencoded = true)
        } finally { tmp.delete() }
    }

    /** Returns the IPTC digital source type that must be preserved, if the image declares AI involvement. */
    fun aiDisclosure(a: ImageAnalysis): String? {
        val candidates = listOfNotNull(a.xmp?.digitalSourceType) + a.iptc?.raw?.values.orEmpty() + a.c2pa.aiSourceTypes
        val st = candidates.mapNotNull { AiPatterns.matchSourceType(it) }
            .firstOrNull { it.cls == AiPatterns.SourceTypeClass.AI_GENERATED || it.cls == AiPatterns.SourceTypeClass.AI_ASSISTED }
        if (st != null) return st.code
        return when (a.ai.verdict) {
            AiVerdict.AI_GENERATED -> AiPatterns.SourceType.TRAINED_ALGORITHMIC.code
            AiVerdict.AI_ASSISTED -> AiPatterns.SourceType.COMPOSITE_TRAINED.code
            else -> null
        }
    }

    private fun label(tag: String) = ExifReader.TAGS.firstOrNull { it.first == tag }?.second ?: tag
}
