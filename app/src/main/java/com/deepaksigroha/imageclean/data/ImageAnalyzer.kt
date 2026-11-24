package com.deepaksigroha.imageclean.data

import android.content.Context
import android.net.Uri
import com.deepaksigroha.imageclean.core.ai.AiProvenanceScanner
import com.deepaksigroha.imageclean.core.ai.AiScanResult
import com.deepaksigroha.imageclean.core.c2pa.C2paReader
import com.deepaksigroha.imageclean.core.c2pa.C2paReport
import com.deepaksigroha.imageclean.core.container.ContainerScanner
import com.deepaksigroha.imageclean.core.meta.IptcParser
import com.deepaksigroha.imageclean.core.meta.XmpParser
import com.deepaksigroha.imageclean.core.model.ContainerInfo
import com.deepaksigroha.imageclean.core.model.ExifCategory
import com.deepaksigroha.imageclean.core.model.IptcData
import com.deepaksigroha.imageclean.core.model.XmpData

/** Everything the app knows about one image. */
class ImageAnalysis(
    val image: LoadedImage,
    val width: Int,
    val height: Int,
    val container: ContainerInfo,
    val exif: ExifSnapshot,
    val iptc: IptcData?,
    val xmp: XmpData?,
    val c2pa: C2paReport,
    val ai: AiScanResult,
) {
    val name get() = image.name
    val size get() = image.bytes.size.toLong()
    val format get() = container.format
    val hasGps get() = exif.latLong != null || exif.has(ExifCategory.LOCATION)
    val hasExif get() = exif.hasExif || container.hasExif
    val metadataStatus: String
        get() {
            val parts = listOfNotNull(
                "EXIF".takeIf { hasExif }, "GPS".takeIf { hasGps }, "XMP".takeIf { container.hasXmp },
                "IPTC".takeIf { container.hasIptc }, "C2PA".takeIf { container.hasC2pa }, "ICC".takeIf { container.hasIcc },
                "Text chunks".takeIf { container.textChunks.isNotEmpty() },
            )
            return if (parts.isEmpty()) "No metadata found" else parts.joinToString(" · ")
        }
}

class ImageAnalyzer(private val ctx: Context, private val trust: TrustStore) {

    fun analyze(uri: Uri): ImageAnalysis = analyze(ImageIO.load(ctx, uri))

    fun analyze(img: LoadedImage): ImageAnalysis {
        val bytes = img.bytes
        val container = ContainerScanner.scan(bytes)
        val exif = ExifReader.read(bytes)
        val iptc = container.iptcIim?.let { runCatching { IptcParser.parse(it) }.getOrNull() }
        val xmp = if (container.xmpPackets.isNotEmpty()) runCatching { XmpParser.parse(container.xmpPackets) }.getOrNull() else null
        val c2pa = runCatching { C2paReader(trust).read(container.c2paJumbf, bytes) }.getOrElse {
            if (container.c2paJumbf != null) C2paReport(com.deepaksigroha.imageclean.core.c2pa.C2paState.UNVERIFIABLE,
                "Manifest could not be processed: ${it.message}", emptyList(), emptyList(), emptyList()) else C2paReport.NONE
        }
        val ai = AiProvenanceScanner.scan(container, exif.provenance, iptc, xmp, c2pa)
        val (w, h) = ImageIO.dimensions(bytes)
        return ImageAnalysis(img, w, h, container, exif, iptc, xmp, c2pa, ai)
    }
}
