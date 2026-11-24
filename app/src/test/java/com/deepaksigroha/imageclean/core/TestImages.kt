package com.deepaksigroha.imageclean.core

import com.deepaksigroha.imageclean.core.c2pa.CborEncoder
import com.deepaksigroha.imageclean.core.c2pa.CborValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Builds small synthetic image files byte-by-byte for parser tests. */
object TestImages {

    fun seg(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), marker.toByte()) + Bytes.be16(payload.size + 2) + payload

    /** Minimal "JPEG": SOI, header segments, a fake SOS with a few entropy bytes, EOI. */
    fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(seg(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        segments.forEach { out.write(it) }
        out.write(seg(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)))
        out.write(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    fun xmpSeg(xmp: String) = seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray())
    fun exifSeg() = seg(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0))
    fun comSeg(text: String) = seg(0xFE, text.toByteArray())

    fun iptcSeg(vararg ds: Pair<Int, String>): ByteArray {
        val iim = ByteArrayOutputStream()
        ds.forEach { (n, v) -> val b = v.toByteArray(); iim.write(byteArrayOf(0x1C, 2, n.toByte())); iim.write(Bytes.be16(b.size)); iim.write(b) }
        val data = iim.toByteArray()
        val irb = "8BIM".toByteArray() + Bytes.be16(0x0404) + byteArrayOf(0, 0) + Bytes.be32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        return seg(0xED, "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1) + irb)
    }

    // ------------------------------------------------------------------ JUMBF / C2PA

    private val C2PA_UUID = ByteArray(16) { it.toByte() }

    fun box(type: String, payload: ByteArray) = Bytes.be32((payload.size + 8).toLong()) + type.toByteArray(Charsets.ISO_8859_1) + payload

    fun jumd(label: String) = box("jumd", C2PA_UUID + byteArrayOf(0x03) + label.toByteArray() + byteArrayOf(0))

    fun superbox(label: String, vararg children: ByteArray) = box("jumb", jumd(label) + children.fold(ByteArray(0)) { a, b -> a + b })

    fun cbor(v: CborValue) = CborEncoder.encode(v)

    fun map(vararg kv: Pair<String, CborValue>) = CborValue.Map(LinkedHashMap<CborValue, CborValue>().apply { kv.forEach { put(CborValue.Text(it.first), it.second) } })

    /** Builds a C2PA manifest store with an AI 'created' action, a data-hash binding and an (unsigned) signature box. */
    fun c2paStore(dataHash: ByteArray, exclusionStart: Long, exclusionLength: Long): ByteArray {
        val actions = superbox("c2pa.actions", box("cbor", cbor(map("actions" to CborValue.Arr(listOf(
            map("action" to CborValue.Text("c2pa.created"),
                "digitalSourceType" to CborValue.Text("http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"),
                "softwareAgent" to CborValue.Text("Test Generator 1.0")),
        ))))))
        val hash = superbox("c2pa.hash.data", box("cbor", cbor(map(
            "exclusions" to CborValue.Arr(listOf(map("start" to CborValue.UInt(exclusionStart), "length" to CborValue.UInt(exclusionLength)))),
            "alg" to CborValue.Text("sha256"),
            "hash" to CborValue.Bytes(dataHash),
        ))))
        val assertions = superbox("c2pa.assertions", actions, hash)
        val claim = superbox("c2pa.claim", box("cbor", cbor(map(
            "claim_generator" to CborValue.Text("Test Generator/1.0"),
            "dc:format" to CborValue.Text("image/jpeg"),
            "alg" to CborValue.Text("sha256"),
            "assertions" to CborValue.Arr(listOf(
                map("url" to CborValue.Text("self#jumbf=c2pa.assertions/c2pa.actions"), "hash" to CborValue.Bytes(sha256(actions.copyOfRange(8, actions.size)))),
                map("url" to CborValue.Text("self#jumbf=c2pa.assertions/c2pa.hash.data"), "hash" to CborValue.Bytes(sha256(hash.copyOfRange(8, hash.size)))),
            )),
        ))))
        // COSE_Sign1 with no certificate – signature validation must FAIL.
        val cose = cbor(CborValue.Tag(18, CborValue.Arr(listOf(CborValue.Bytes(cbor(CborValue.Map(linkedMapOf<CborValue, CborValue>(CborValue.UInt(1) to CborValue.NInt(-7))))),
            CborValue.Map(LinkedHashMap()), CborValue.Null, CborValue.Bytes(ByteArray(64))))))
        val sig = superbox("c2pa.signature", box("cbor", cose))
        val manifest = superbox("urn:uuid:test-manifest", assertions, claim, sig)
        return superbox("c2pa", manifest)
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** JPEG with a C2PA manifest in APP11, with a correct data hash over the file excluding the APP11 segment. */
    fun jpegWithC2pa(tamper: Boolean = false): ByteArray {
        // First pass to learn sizes (hash value length is fixed, so the layout is stable).
        fun build(hash: ByteArray, start: Long, len: Long): ByteArray {
            val store = c2paStore(hash, start, len)
            val app11 = seg(0xEB, "JP".toByteArray() + Bytes.be16(1) + Bytes.be32(1) + store)
            return jpeg(app11)
        }
        val tmp = build(ByteArray(32), 0, 0)
        val start = 2L + 18 // SOI + APP0 (2+2+14)
        val len = (tmp.size - jpeg().size).toLong()
        val withPlaceholder = build(ByteArray(32), start, len)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(withPlaceholder, 0, start.toInt())
        md.update(withPlaceholder, (start + len).toInt(), withPlaceholder.size - (start + len).toInt())
        val real = build(md.digest(), start, len)
        if (tamper) real[real.size - 4] = (real[real.size - 4] + 1).toByte()
        return real
    }

    // ------------------------------------------------------------------ PNG

    fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun ch(t: String, d: ByteArray) { val tb = t.toByteArray(); out.write(Bytes.be32(d.size.toLong())); out.write(tb); out.write(d)
            out.write(Bytes.be32(CRC32().apply { update(tb); update(d) }.value)) }
        ch("IHDR", Bytes.be32(1) + Bytes.be32(1) + byteArrayOf(8, 2, 0, 0, 0))
        chunks.forEach { (t, d) -> ch(t, d) }
        ch("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0x60, 0x60, 0x60, 0, 0, 0, 4, 0, 1))
        ch("IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun text(k: String, v: String) = "tEXt" to (k.toByteArray() + byteArrayOf(0) + v.toByteArray(Charsets.ISO_8859_1))
}
