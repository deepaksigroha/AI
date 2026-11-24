package com.deepaksigroha.imageclean.core.c2pa

import com.deepaksigroha.imageclean.core.Bytes

/**
 * JUMBF (ISO/IEC 19566-5) box tree as used by C2PA manifest stores.
 * offsets are relative to the manifest-store byte array that was parsed.
 */
class JumbfBox(
    val type: String,
    val start: Int,        // offset of the box header (LBox)
    val headerSize: Int,
    val end: Int,          // exclusive
    val label: String?,    // from jumd, for superboxes
    val children: List<JumbfBox>,
    val contentType: String?, // for jumb superboxes: first content box type (cbor/json/…)
) {
    val payloadStart get() = start + headerSize

    fun child(label: String): JumbfBox? = children.firstOrNull { it.label == label }
    fun childrenSuperboxes() = children.filter { it.type == "jumb" }

    /** First non-jumd child – the content box of a superbox. */
    fun contentBox(): JumbfBox? = children.firstOrNull { it.type != "jumd" && it.type != "jumb" }
}

object Jumbf {
    fun parse(b: ByteArray): JumbfBox? = try { parseBox(b, 0, b.size, 0) } catch (_: Exception) { null }

    private fun parseBox(b: ByteArray, start: Int, limit: Int, depth: Int): JumbfBox? {
        if (depth > 16 || start + 8 > limit) return null
        var size = Bytes.u32be(b, start)
        val type = Bytes.ascii(b, start + 4, 4)
        var header = 8
        if (size == 1L) { size = Bytes.u64be(b, start + 8); header = 16 }
        if (size == 0L) size = (limit - start).toLong()
        if (size < header || start + size > limit) return null
        val end = (start + size).toInt()
        if (type != "jumb") return JumbfBox(type, start, header, end, null, emptyList(), null)
        val kids = ArrayList<JumbfBox>()
        var p = start + header
        var label: String? = null
        while (p + 8 <= end) {
            val k = parseBox(b, p, end, depth + 1) ?: break
            if (k.type == "jumd") label = readLabel(b, k)
            kids += k
            p = k.end
        }
        val contentType = kids.firstOrNull { it.type != "jumd" }?.type
        return JumbfBox(type, start, header, end, label, kids, contentType)
    }

    /** jumd payload: UUID(16) toggles(1) [label\0] [id(4)] [sig(32)] */
    private fun readLabel(b: ByteArray, jumd: JumbfBox): String? {
        val p = jumd.payloadStart
        if (p + 17 > jumd.end) return null
        val toggles = Bytes.u8(b, p + 16)
        if (toggles and 0x02 == 0) return null
        val ls = p + 17
        val nul = Bytes.indexOf(b, byteArrayOf(0), ls, jumd.end)
        return if (nul < 0) null else String(b, ls, nul - ls, Charsets.UTF_8)
    }
}
