package com.deepaksigroha.imageclean.core.container

import com.deepaksigroha.imageclean.core.Bytes
import com.deepaksigroha.imageclean.core.model.BlockKind
import com.deepaksigroha.imageclean.core.model.ContainerInfo
import com.deepaksigroha.imageclean.core.model.ImageFormat
import com.deepaksigroha.imageclean.core.model.MetadataBlock
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Walks the file container (JPEG segments, PNG chunks, RIFF/WebP chunks, ISO-BMFF boxes)
 * and collects every metadata block. Pure Kotlin – no Android dependencies, so it is unit tested on the JVM.
 */
object ContainerScanner {

    val XMP_SIG = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    val XMP_EXT_SIG = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
    val EXIF_SIG = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    val PS_SIG = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1)
    val ICC_SIG = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)

    /** UUID used by C2PA for embedding manifests in ISO-BMFF files. */
    private val C2PA_BMFF_UUID = byteArrayOf(
        0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(), 0x1B, 0x0E, 0x48, 0x3C,
        0x92.toByte(), 0x97.toByte(), 0x58, 0x28, 0x87.toByte(), 0x7E, 0xC4.toByte(), 0x81.toByte(),
    )

    fun scan(bytes: ByteArray): ContainerInfo = when (val f = ImageFormat.detect(bytes)) {
        ImageFormat.JPEG -> scanJpeg(bytes)
        ImageFormat.PNG -> scanPng(bytes)
        ImageFormat.WEBP -> scanWebp(bytes)
        ImageFormat.HEIF, ImageFormat.AVIF -> scanBmff(bytes, f)
        ImageFormat.UNKNOWN -> ContainerInfo(f, emptyList(), findXmpPackets(bytes), null, null, null, emptyList(), emptyMap(),
            listOf("Unrecognised file format – only a generic scan was possible."))
    }

    // ---------------------------------------------------------------- JPEG

    data class JpegSegment(val marker: Int, val offset: Int, val totalLength: Int, val dataStart: Int, val dataLength: Int)

    /** Returns the header segments of a JPEG up to (not including) the SOS marker. */
    fun jpegSegments(b: ByteArray): Pair<List<JpegSegment>, Int> {
        val list = ArrayList<JpegSegment>()
        var i = 2
        while (i + 4 <= b.size) {
            if (Bytes.u8(b, i) != 0xFF) break
            var marker = Bytes.u8(b, i + 1)
            // skip fill bytes
            while (marker == 0xFF && i + 2 < b.size) { i++; marker = Bytes.u8(b, i + 1) }
            if (marker == 0xDA || marker == 0xD9) return list to i // start of scan / EOI
            if (marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            val len = Bytes.u16be(b, i + 2)
            if (len < 2 || i + 2 + len > b.size) break
            list += JpegSegment(marker, i, len + 2, i + 4, len - 2)
            i += 2 + len
        }
        return list to i
    }

    private fun scanJpeg(b: ByteArray): ContainerInfo {
        val blocks = ArrayList<MetadataBlock>()
        val xmp = ArrayList<String>()
        val comments = ArrayList<String>()
        val warnings = ArrayList<String>()
        var iptc: ByteArray? = null
        var iccDesc: String? = null
        val iccParts = ArrayList<ByteArray>()
        val extXmp = HashMap<String, ByteArrayOutputStream>()
        // C2PA APP11 packets: key = box instance (En), value = list of (seq, payload)
        val jumbfPackets = HashMap<Int, MutableList<Pair<Long, ByteArray>>>()

        val (segs, _) = jpegSegments(b)
        for (s in segs) {
            when (s.marker) {
                0xE1 -> when {
                    Bytes.startsWith(b, s.dataStart, EXIF_SIG) -> blocks += MetadataBlock(BlockKind.EXIF, s.offset, s.totalLength)
                    Bytes.startsWith(b, s.dataStart, XMP_SIG) -> {
                        blocks += MetadataBlock(BlockKind.XMP, s.offset, s.totalLength)
                        val st = s.dataStart + XMP_SIG.size
                        xmp += String(b, st, s.dataLength - XMP_SIG.size, Charsets.UTF_8)
                    }
                    Bytes.startsWith(b, s.dataStart, XMP_EXT_SIG) -> {
                        blocks += MetadataBlock(BlockKind.XMP_EXTENDED, s.offset, s.totalLength)
                        val st = s.dataStart + XMP_EXT_SIG.size
                        if (s.dataLength > XMP_EXT_SIG.size + 40) {
                            val guid = Bytes.ascii(b, st, 32)
                            val out = extXmp.getOrPut(guid) { ByteArrayOutputStream() }
                            out.write(b, st + 40, s.dataLength - XMP_EXT_SIG.size - 40)
                        }
                    }
                    else -> blocks += MetadataBlock(BlockKind.OTHER_APP, s.offset, s.totalLength, "APP1")
                }
                0xE2 -> if (Bytes.startsWith(b, s.dataStart, ICC_SIG)) {
                    blocks += MetadataBlock(BlockKind.ICC, s.offset, s.totalLength)
                    iccParts += b.copyOfRange(s.dataStart + ICC_SIG.size + 2, s.dataStart + s.dataLength)
                } else blocks += MetadataBlock(BlockKind.OTHER_APP, s.offset, s.totalLength, "APP2")
                0xED -> if (Bytes.startsWith(b, s.dataStart, PS_SIG)) {
                    blocks += MetadataBlock(BlockKind.IPTC, s.offset, s.totalLength)
                    val iim = extractIptcFromIrb(b, s.dataStart + PS_SIG.size, s.dataStart + s.dataLength)
                    if (iim != null) iptc = (iptc ?: ByteArray(0)) + iim
                }
                0xEB -> {
                    // APP11: CI "JP" (2) En (2) Z (4) then JUMBF box data
                    if (s.dataLength > 8 && Bytes.ascii(b, s.dataStart, 2) == "JP") {
                        val en = Bytes.u16be(b, s.dataStart + 2)
                        val z = Bytes.u32be(b, s.dataStart + 4)
                        val payload = b.copyOfRange(s.dataStart + 8, s.dataStart + s.dataLength)
                        jumbfPackets.getOrPut(en) { ArrayList() } += z to payload
                        blocks += MetadataBlock(BlockKind.C2PA, s.offset, s.totalLength, "APP11 JUMBF en=$en seq=$z")
                    } else blocks += MetadataBlock(BlockKind.OTHER_APP, s.offset, s.totalLength, "APP11")
                }
                0xFE -> {
                    blocks += MetadataBlock(BlockKind.COMMENT, s.offset, s.totalLength)
                    comments += String(b, s.dataStart, s.dataLength, Charsets.UTF_8).trim('\u0000', ' ')
                }
                in 0xE0..0xEF -> if (s.marker != 0xE0) {
                    blocks += MetadataBlock(BlockKind.OTHER_APP, s.offset, s.totalLength, "APP${s.marker - 0xE0}")
                }
            }
        }
        extXmp.values.forEach { xmp += it.toString(Charsets.UTF_8.name()) }
        if (iccParts.isNotEmpty()) iccDesc = iccDescription(iccParts.fold(ByteArray(0)) { a, p -> a + p })

        var c2pa: ByteArray? = null
        for ((_, packets) in jumbfPackets) {
            val sorted = packets.sortedBy { it.first }
            val out = ByteArrayOutputStream()
            sorted.forEachIndexed { idx, (_, data) ->
                if (idx == 0) out.write(data) else {
                    // Continuation packets repeat LBox/TBox (and XLBox if LBox == 1) – strip them.
                    val skip = if (data.size >= 8 && Bytes.u32be(data, 0) == 1L) 16 else 8
                    if (data.size > skip) out.write(data, skip, data.size - skip)
                }
            }
            val box = out.toByteArray()
            if (box.size > 8 && Bytes.ascii(box, 4, 4) == "jumb" && boxLabelIsC2pa(box)) { c2pa = box; break }
        }
        if (jumbfPackets.isNotEmpty() && c2pa == null) warnings += "JUMBF data found but it is not a C2PA manifest store."
        return ContainerInfo(ImageFormat.JPEG, blocks, xmp, iptc, c2pa, iccDesc, comments, emptyMap(), warnings)
    }

    /** Extracts IPTC-IIM (resource 0x0404) from a Photoshop Image Resource Block. */
    fun extractIptcFromIrb(b: ByteArray, start: Int, end: Int): ByteArray? {
        var i = start
        while (i + 12 <= end) {
            if (Bytes.ascii(b, i, 4) != "8BIM") return null
            val id = Bytes.u16be(b, i + 4)
            val nameLen = Bytes.u8(b, i + 6)
            var p = i + 6 + 1 + nameLen
            if ((1 + nameLen) % 2 != 0) p++
            if (p + 4 > end) return null
            val size = Bytes.u32be(b, p).toInt()
            p += 4
            if (size < 0 || p + size > end) return null
            if (id == 0x0404) return b.copyOfRange(p, p + size)
            i = p + size + (size and 1)
        }
        return null
    }

    // ---------------------------------------------------------------- PNG

    data class PngChunk(val type: String, val offset: Int, val dataStart: Int, val length: Int)

    fun pngChunks(b: ByteArray): List<PngChunk> {
        val list = ArrayList<PngChunk>()
        var i = 8
        while (i + 12 <= b.size) {
            val len = Bytes.u32be(b, i).toInt()
            val type = Bytes.ascii(b, i + 4, 4)
            if (len < 0 || i + 12 + len > b.size) break
            list += PngChunk(type, i, i + 8, len)
            i += 12 + len
            if (type == "IEND") break
        }
        return list
    }

    private fun scanPng(b: ByteArray): ContainerInfo {
        val blocks = ArrayList<MetadataBlock>()
        val xmp = ArrayList<String>()
        val text = LinkedHashMap<String, String>()
        val warnings = ArrayList<String>()
        var c2pa: ByteArray? = null
        var iccDesc: String? = null
        for (c in pngChunks(b)) {
            val total = c.length + 12
            when (c.type) {
                "eXIf" -> blocks += MetadataBlock(BlockKind.EXIF, c.offset, total)
                "iCCP" -> {
                    blocks += MetadataBlock(BlockKind.ICC, c.offset, total)
                    val nul = Bytes.indexOf(b, byteArrayOf(0), c.dataStart, c.dataStart + c.length)
                    if (nul > 0) {
                        val profile = inflate(b.copyOfRange(nul + 2, c.dataStart + c.length))
                        iccDesc = profile?.let { iccDescription(it) } ?: Bytes.ascii(b, c.dataStart, nul - c.dataStart)
                    }
                }
                "caBX" -> {
                    blocks += MetadataBlock(BlockKind.C2PA, c.offset, total, "caBX chunk")
                    c2pa = b.copyOfRange(c.dataStart, c.dataStart + c.length)
                }
                "tEXt" -> {
                    val nul = Bytes.indexOf(b, byteArrayOf(0), c.dataStart, c.dataStart + c.length)
                    if (nul > 0) {
                        val k = Bytes.ascii(b, c.dataStart, nul - c.dataStart)
                        text[k] = String(b, nul + 1, c.dataStart + c.length - nul - 1, Charsets.ISO_8859_1)
                        blocks += MetadataBlock(BlockKind.TEXT, c.offset, total, "tEXt: $k")
                        if (k == "Raw profile type iptc" || k == "Raw profile type exif") warnings += "Legacy ImageMagick raw profile: $k"
                    }
                }
                "zTXt" -> {
                    val nul = Bytes.indexOf(b, byteArrayOf(0), c.dataStart, c.dataStart + c.length)
                    if (nul > 0) {
                        val k = Bytes.ascii(b, c.dataStart, nul - c.dataStart)
                        val v = inflate(b.copyOfRange(nul + 2, c.dataStart + c.length))
                        text[k] = v?.toString(Charsets.ISO_8859_1) ?: ""
                        blocks += MetadataBlock(BlockKind.TEXT, c.offset, total, "zTXt: $k")
                    }
                }
                "iTXt" -> {
                    val end = c.dataStart + c.length
                    val nul = Bytes.indexOf(b, byteArrayOf(0), c.dataStart, end)
                    if (nul > 0 && nul + 3 < end) {
                        val k = Bytes.ascii(b, c.dataStart, nul - c.dataStart)
                        val compressed = b[nul + 1].toInt() == 1
                        var p = nul + 3
                        val langEnd = Bytes.indexOf(b, byteArrayOf(0), p, end); if (langEnd < 0) continue
                        p = langEnd + 1
                        val transEnd = Bytes.indexOf(b, byteArrayOf(0), p, end); if (transEnd < 0) continue
                        p = transEnd + 1
                        val raw = b.copyOfRange(p, end)
                        val value = (if (compressed) inflate(raw) else raw)?.toString(Charsets.UTF_8) ?: ""
                        if (k == "XML:com.adobe.xmp") {
                            xmp += value
                            blocks += MetadataBlock(BlockKind.XMP, c.offset, total, "iTXt XMP")
                        } else {
                            text[k] = value
                            blocks += MetadataBlock(BlockKind.TEXT, c.offset, total, "iTXt: $k")
                        }
                    }
                }
            }
        }
        return ContainerInfo(ImageFormat.PNG, blocks, xmp, null, c2pa, iccDesc, emptyList(), text, warnings)
    }

    // ---------------------------------------------------------------- WebP

    data class RiffChunk(val fourcc: String, val offset: Int, val dataStart: Int, val length: Int)

    fun riffChunks(b: ByteArray): List<RiffChunk> {
        val list = ArrayList<RiffChunk>()
        var i = 12
        while (i + 8 <= b.size) {
            val id = Bytes.ascii(b, i, 4)
            val len = Bytes.u32le(b, i + 4).toInt()
            if (len < 0 || i + 8 + len > b.size) break
            list += RiffChunk(id, i, i + 8, len)
            i += 8 + len + (len and 1)
        }
        return list
    }

    private fun scanWebp(b: ByteArray): ContainerInfo {
        val blocks = ArrayList<MetadataBlock>()
        val xmp = ArrayList<String>()
        var c2pa: ByteArray? = null
        var iccDesc: String? = null
        for (c in riffChunks(b)) {
            val total = 8 + c.length + (c.length and 1)
            when (c.fourcc) {
                "EXIF" -> blocks += MetadataBlock(BlockKind.EXIF, c.offset, total)
                "XMP " -> {
                    blocks += MetadataBlock(BlockKind.XMP, c.offset, total)
                    xmp += String(b, c.dataStart, c.length, Charsets.UTF_8)
                }
                "ICCP" -> {
                    blocks += MetadataBlock(BlockKind.ICC, c.offset, total)
                    iccDesc = iccDescription(b.copyOfRange(c.dataStart, c.dataStart + c.length))
                }
                "C2PA" -> {
                    blocks += MetadataBlock(BlockKind.C2PA, c.offset, total, "C2PA chunk")
                    c2pa = b.copyOfRange(c.dataStart, c.dataStart + c.length)
                }
            }
        }
        return ContainerInfo(ImageFormat.WEBP, blocks, xmp, null, c2pa, iccDesc, emptyList(), emptyMap(), emptyList())
    }

    // ---------------------------------------------------------------- HEIF / AVIF (ISO-BMFF)

    private fun scanBmff(b: ByteArray, format: ImageFormat): ContainerInfo {
        val blocks = ArrayList<MetadataBlock>()
        val warnings = ArrayList<String>()
        var c2pa: ByteArray? = null
        // Top-level boxes
        var i = 0
        while (i + 8 <= b.size) {
            var size = Bytes.u32be(b, i)
            val type = Bytes.ascii(b, i + 4, 4)
            var header = 8
            if (size == 1L && i + 16 <= b.size) { size = Bytes.u64be(b, i + 8); header = 16 }
            if (size == 0L) size = (b.size - i).toLong()
            if (size < header || i + size > b.size) break
            if (type == "uuid" && Bytes.startsWith(b, i + header, C2PA_BMFF_UUID)) {
                // FullBox: version(1)+flags(3), purpose (null-terminated), [offset(8)], data
                var p = i + header + 16 + 4
                val nul = Bytes.indexOf(b, byteArrayOf(0), p, (i + size).toInt())
                if (nul > 0) {
                    val purpose = Bytes.ascii(b, p, nul - p)
                    p = nul + 1
                    if (purpose == "manifest") {
                        p += 8
                        c2pa = b.copyOfRange(p, (i + size).toInt())
                        blocks += MetadataBlock(BlockKind.C2PA, i, size.toInt(), "uuid C2PA box")
                    }
                }
            }
            i += size.toInt()
        }
        if (c2pa == null) c2pa = searchJumbfC2pa(b)
        // Exif & XMP in HEIF live inside 'meta'/'iloc' items; ExifInterface reads EXIF. Detect presence heuristically.
        if (Bytes.indexOf(b, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)) >= 0 ||
            Bytes.indexOf(b, byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)) >= 0 || Bytes.indexOf(b, byteArrayOf(0x49, 0x49, 0x2A, 0x00)) >= 0
        ) blocks += MetadataBlock(BlockKind.EXIF, -1, 0, "Exif item")
        val xmp = findXmpPackets(b)
        if (xmp.isNotEmpty()) blocks += MetadataBlock(BlockKind.XMP, -1, 0, "XMP item")
        if (Bytes.indexOf(b, "colr".toByteArray()) >= 0) blocks += MetadataBlock(BlockKind.ICC, -1, 0, "colr property")
        warnings += "HEIF/AVIF metadata is read with a structural scan; editing requires re-encoding to JPEG."
        return ContainerInfo(format, blocks, xmp, null, c2pa, null, emptyList(), emptyMap(), warnings)
    }

    // ---------------------------------------------------------------- helpers

    /** Fallback: find a JUMBF superbox whose description label is "c2pa" anywhere in the file. */
    fun searchJumbfC2pa(b: ByteArray): ByteArray? {
        val needle = "jumb".toByteArray(Charsets.ISO_8859_1)
        var from = 4
        while (true) {
            val at = Bytes.indexOf(b, needle, from)
            if (at < 4) return null
            val start = at - 4
            val size = Bytes.u32be(b, start)
            if (size in 24..(b.size - start).toLong()) {
                val box = b.copyOfRange(start, start + size.toInt())
                if (boxLabelIsC2pa(box)) return box
            }
            from = at + 4
        }
    }

    private fun boxLabelIsC2pa(box: ByteArray): Boolean {
        // jumb(8) + jumd box: size(4) "jumd"(4) uuid(16) toggles(1) label...
        if (box.size < 8 + 8 + 17 + 4) return false
        if (Bytes.ascii(box, 12, 4) != "jumd") return false
        val labelStart = 8 + 8 + 17
        val nul = Bytes.indexOf(box, byteArrayOf(0), labelStart, minOf(box.size, labelStart + 64))
        if (nul < 0) return false
        return Bytes.ascii(box, labelStart, nul - labelStart) == "c2pa"
    }

    fun findXmpPackets(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val start = "<x:xmpmeta".toByteArray()
        val end = "</x:xmpmeta>".toByteArray()
        var from = 0
        while (true) {
            val s = Bytes.indexOf(b, start, from); if (s < 0) break
            val e = Bytes.indexOf(b, end, s); if (e < 0) break
            out += String(b, s, e + end.size - s, Charsets.UTF_8)
            from = e + end.size
        }
        return out
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var guard = 0
        while (!inf.finished() && guard++ < 100_000) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        out.toByteArray()
    } catch (_: Exception) { null }

    /** Reads the 'desc' tag text from an ICC profile. */
    fun iccDescription(p: ByteArray): String? {
        if (p.size < 132) return null
        val count = Bytes.u32be(p, 128).toInt()
        for (k in 0 until minOf(count, 100)) {
            val e = 132 + k * 12
            if (e + 12 > p.size) break
            if (Bytes.ascii(p, e, 4) == "desc") {
                val off = Bytes.u32be(p, e + 4).toInt()
                if (off + 12 > p.size) return null
                return when (Bytes.ascii(p, off, 4)) {
                    "desc" -> {
                        val n = Bytes.u32be(p, off + 8).toInt()
                        Bytes.ascii(p, off + 12, minOf(n, p.size - off - 12)).trim('\u0000')
                    }
                    "mluc" -> {
                        val recLen = Bytes.u32be(p, off + 16 + 4).toInt()
                        val recOff = Bytes.u32be(p, off + 16 + 8).toInt()
                        if (off + recOff + recLen > p.size) null
                        else String(p, off + recOff, recLen, Charsets.UTF_16BE).trim('\u0000')
                    }
                    else -> null
                }
            }
        }
        return null
    }
}
