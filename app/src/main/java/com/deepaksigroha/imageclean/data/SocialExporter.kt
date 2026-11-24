package com.deepaksigroha.imageclean.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.deepaksigroha.imageclean.core.clean.JpegRewriter
import com.deepaksigroha.imageclean.core.clean.PngRewriter
import com.deepaksigroha.imageclean.core.clean.XmpBuilder
import com.deepaksigroha.imageclean.core.model.ImageFormat
import java.io.ByteArrayOutputStream

data class ExportPreset(
    val name: String,
    val aspectW: Int,  // 0 = keep original
    val aspectH: Int,
    val maxLongEdge: Int,
    val png: Boolean = false,
    val quality: Int = 90,
    val custom: Boolean = false,
    val id: Long = 0,
) {
    val ratioLabel get() = if (aspectW == 0) "Original ratio" else "$aspectW:$aspectH"

    companion object {
        val BUILT_IN = listOf(
            ExportPreset("Instagram Feed (portrait)", 4, 5, 1350),
            ExportPreset("Instagram Feed (square)", 1, 1, 1080),
            ExportPreset("Instagram Stories", 9, 16, 1920),
            ExportPreset("Instagram Reels cover", 9, 16, 1920),
            ExportPreset("Facebook", 0, 0, 2048),
            ExportPreset("WhatsApp", 0, 0, 1600, quality = 85),
            ExportPreset("LinkedIn", 0, 0, 1920),
            ExportPreset("LinkedIn (link post 1.91:1)", 191, 100, 1200),
            ExportPreset("X (Twitter) 16:9", 16, 9, 1600),
            ExportPreset("X (Twitter) original", 0, 0, 2048),
        )
    }
}

data class ExportOptions(
    val preset: ExportPreset,
    val keepCreatorAndCopyright: Boolean = true,
    val cropOffsetX: Float = 0.5f, // 0..1 – where the crop window sits horizontally
    val cropOffsetY: Float = 0.5f,
)

data class ExportResult(
    val bytes: ByteArray,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val removed: List<String>,
    val retained: List<String>,
    val compression: String,
    val warnings: List<String>,
)

/** Resizes/crops for a platform and re-encodes. Authenticity information is carried over, never silently dropped. */
class SocialExporter(private val cleaner: MetadataCleaner) {

    fun render(a: ImageAnalysis, o: ExportOptions, maxPreviewEdge: Int = 0): Bitmap {
        val p = o.preset
        val src = ImageIO.decode(a.image.bytes, 4096)
        val (cw, ch) = cropSize(src.width, src.height, p)
        val left = ((src.width - cw) * o.cropOffsetX.coerceIn(0f, 1f)).toInt()
        val top = ((src.height - ch) * o.cropOffsetY.coerceIn(0f, 1f)).toInt()
        val long = maxOf(cw, ch)
        val limit = if (maxPreviewEdge > 0) minOf(maxPreviewEdge, p.maxLongEdge) else p.maxLongEdge
        val scale = if (long > limit) limit.toDouble() / long else 1.0
        val ow = maxOf(1, (cw * scale).toInt()); val oh = maxOf(1, (ch * scale).toInt())
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
        return out
    }

    fun export(a: ImageAnalysis, o: ExportOptions): ExportResult {
        val bmp = render(a, o)
        val baos = ByteArrayOutputStream()
        val fmt = if (o.preset.png) ImageFormat.PNG else ImageFormat.JPEG
        bmp.compress(if (o.preset.png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, o.preset.quality.coerceIn(40, 100), baos)
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        var bytes = baos.toByteArray()

        val retained = ArrayList<String>(); val warnings = ArrayList<String>()
        val aiDst = cleaner.aiDisclosure(a)
        val creator = if (o.keepCreatorAndCopyright) (a.xmp?.creators?.firstOrNull() ?: a.iptc?.creator?.firstOrNull()
            ?: a.exif.fields.firstOrNull { it.tag == "Artist" }?.value) else null
        val rights = if (o.keepCreatorAndCopyright) (a.xmp?.rights ?: a.iptc?.copyright
            ?: a.exif.fields.firstOrNull { it.tag == "Copyright" }?.value) else null
        if (aiDst != null || creator != null || rights != null) {
            val xmp = XmpBuilder.disclosurePacket(aiDst, creator, rights, note = "Exported with ImageClean Pro")
            bytes = if (fmt == ImageFormat.JPEG) JpegRewriter.removeSegments(bytes, emptySet(), xmp) else PngRewriter.rewrite(bytes, { _, _ -> false }, xmp)
            aiDst?.let { retained += "AI disclosure (IPTC DigitalSourceType: $it)" }
            creator?.let { retained += "Creator: $it" }
            rights?.let { retained += "Copyright: $it" }
        }
        if (a.container.hasC2pa) warnings += "C2PA Content Credentials cannot survive resizing/re-encoding; the AI disclosure, if any, is carried as IPTC metadata instead."
        if (a.container.hasIcc && a.container.iccDescription?.contains("sRGB", true) == false)
            warnings += "Colours were converted from '${a.container.iccDescription}' to sRGB for web display."
        val removed = listOf("All camera EXIF (GPS, device, timestamps)", "Original XMP/IPTC blocks", "Embedded thumbnail")
            .filter { a.hasExif || a.container.hasXmp || a.container.hasIptc }
        val comp = if (fmt == ImageFormat.PNG) "PNG lossless" else "JPEG quality ${o.preset.quality}, sRGB, ${w}×$h"
        return ExportResult(bytes, fmt, w, h, removed, retained, comp, warnings)
    }

    companion object {
        fun cropSize(w: Int, h: Int, p: ExportPreset): Pair<Int, Int> {
            if (p.aspectW == 0 || p.aspectH == 0) return w to h
            val target = p.aspectW.toDouble() / p.aspectH
            val cur = w.toDouble() / h
            return if (cur > target) (h * target).toInt().coerceAtLeast(1) to h else w to (w / target).toInt().coerceAtLeast(1)
        }
    }
}
