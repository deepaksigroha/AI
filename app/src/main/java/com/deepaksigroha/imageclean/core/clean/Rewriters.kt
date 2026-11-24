package com.deepaksigroha.imageclean.core.clean

import com.deepaksigroha.imageclean.core.Bytes
import com.deepaksigroha.imageclean.core.container.ContainerScanner
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Lossless JPEG header rewriting: segments are dropped or inserted; entropy-coded image data is copied byte for byte. */
object JpegRewriter {

    enum class Seg { EXIF, XMP, XMP_EXT, IPTC_IRB, COMMENT, C2PA, ICC, OTHER }

    fun classify(b: ByteArray, s: ContainerScanner.JpegSegment): Seg = when (s.marker) {
        0xE1 -> when {
            Bytes.startsWith(b, s.dataStart, ContainerScanner.EXIF_SIG) -> Seg.EXIF
            Bytes.startsWith(b, s.dataStart, ContainerScanner.XMP_SIG) -> Seg.XMP
            Bytes.startsWith(b, s.dataStart, ContainerScanner.XMP_EXT_SIG) -> Seg.XMP_EXT
            else -> Seg.OTHER
        }
        0xED -> if (Bytes.startsWith(b, s.dataStart, ContainerScanner.PS_SIG)) Seg.IPTC_IRB else Seg.OTHER
        0xFE -> Seg.COMMENT
        0xEB -> Seg.C2PA
        0xE2 -> if (Bytes.startsWith(b, s.dataStart, ContainerScanner.ICC_SIG)) Seg.ICC else Seg.OTHER
        else -> Seg.OTHER
    }

    /** Removes header segments whose class is in [drop]. C2PA and ICC segments are never removed here. */
    fun removeSegments(b: ByteArray, drop: Set<Seg>, insertXmp: String? = null): ByteArray {
        val safeDrop = drop - Seg.C2PA - Seg.ICC
        val (segs, sosPos) = ContainerScanner.jpegSegments(b)
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2) // SOI
        var xmpWritten = insertXmp == null
        for (s in segs) {
            val cls = classify(b, s)
            if (cls in safeDrop) continue
            if (cls == Seg.XMP && insertXmp != null) continue // replaced below
            out.write(b, s.offset, s.totalLength)
            // Insert new XMP right after APP0/APP1-Exif so it sits where readers expect it.
            if (!xmpWritten && (s.marker == 0xE0 || cls == Seg.EXIF)) { out.write(xmpSegment(insertXmp!!)); xmpWritten = true }
        }
        if (!xmpWritten) {
            // No APP0/APP1 – put XMP immediately after SOI by rebuilding.
            val rest = out.toByteArray().copyOfRange(2, out.size())
            out.reset(); out.write(b, 0, 2); out.write(xmpSegment(insertXmp!!)); out.write(rest)
        }
        out.write(b, sosPos, b.size - sosPos)
        return out.toByteArray()
    }

    fun xmpSegment(xmp: String): ByteArray {
        val payload = ContainerScanner.XMP_SIG + xmp.toByteArray(Charsets.UTF_8)
        require(payload.size + 2 <= 0xFFFF) { "XMP packet too large for one segment" }
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + Bytes.be16(payload.size + 2) + payload
    }
}

/** PNG chunk rewriting with CRC recomputation. Image data (IDAT) is copied untouched. */
object PngRewriter {
    fun rewrite(b: ByteArray, drop: (ContainerScanner.PngChunk, ByteArray) -> Boolean, insertXmp: String? = null): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var inserted = insertXmp == null
        for (c in ContainerScanner.pngChunks(b)) {
            val isXmp = c.type == "iTXt" && Bytes.startsWith(b, c.dataStart, "XML:com.adobe.xmp\u0000".toByteArray(Charsets.ISO_8859_1))
            if (c.type != "IHDR" && c.type != "IDAT" && c.type != "IEND" && c.type != "PLTE" && c.type != "caBX" && drop(c, b)) continue
            if (isXmp && insertXmp != null) continue
            if (!inserted && (c.type == "IDAT" || c.type == "PLTE")) { out.write(chunk("iTXt", itxtPayload("XML:com.adobe.xmp", insertXmp!!))); inserted = true }
            out.write(b, c.offset, c.length + 12)
        }
        return out.toByteArray()
    }

    fun itxtPayload(key: String, text: String): ByteArray =
        key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0, 0) + text.toByteArray(Charsets.UTF_8)

    fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        return Bytes.be32(data.size.toLong()) + t + data + Bytes.be32(crc)
    }
}

/** Builds a minimal XMP packet that preserves an AI disclosure (and optional credit lines). */
object XmpBuilder {
    fun disclosurePacket(digitalSourceType: String?, creator: String? = null, rights: String? = null, note: String? = null): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val dst = digitalSourceType?.let {
            val uri = if (it.startsWith("http")) it else "http://cv.iptc.org/newscodes/digitalsourcetype/$it"
            "\n   Iptc4xmpExt:DigitalSourceType=\"${esc(uri)}\""
        } ?: ""
        val creatorXml = creator?.let { "\n   <dc:creator><rdf:Seq><rdf:li>${esc(it)}</rdf:li></rdf:Seq></dc:creator>" } ?: ""
        val rightsXml = rights?.let { "\n   <dc:rights><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(it)}</rdf:li></rdf:Alt></dc:rights>" } ?: ""
        val noteAttr = note?.let { "\n   xmp:Label=\"${esc(it)}\"" } ?: ""
        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
   xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:xmp="http://ns.adobe.com/xap/1.0/"$dst$noteAttr>$creatorXml$rightsXml
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    /** Adds Iptc4xmpExt:DigitalSourceType to an existing XMP packet, keeping everything else. */
    fun mergeDisclosure(existing: String, digitalSourceType: String): String {
        val uri = if (digitalSourceType.startsWith("http")) digitalSourceType else "http://cv.iptc.org/newscodes/digitalsourcetype/$digitalSourceType"
        val idx = existing.indexOf("<rdf:Description")
        if (idx < 0) return disclosurePacket(digitalSourceType)
        val insertAt = idx + "<rdf:Description".length
        val ns = if (existing.contains("xmlns:Iptc4xmpExt=")) "" else " xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\""
        return existing.substring(0, insertAt) + ns + " Iptc4xmpExt:DigitalSourceType=\"" + uri + "\"" + existing.substring(insertAt)
    }
}
