package com.deepaksigroha.imageclean.core

/** Small, allocation-light helpers for reading big-endian / little-endian binary structures. */
internal object Bytes {
    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    fun u16be(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    fun u16le(b: ByteArray, i: Int): Int = (u8(b, i + 1) shl 8) or u8(b, i)
    fun u32be(b: ByteArray, i: Int): Long =
        ((u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong())
    fun u32le(b: ByteArray, i: Int): Long =
        ((u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or
            (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong())
    fun u64be(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or u8(b, i + k).toLong()
        return v
    }

    fun ascii(b: ByteArray, from: Int, len: Int): String {
        if (from < 0 || len <= 0 || from + len > b.size) return ""
        return String(b, from, len, Charsets.ISO_8859_1)
    }

    fun startsWith(b: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at < 0 || at + prefix.size > b.size) return false
        for (k in prefix.indices) if (b[at + k] != prefix[k]) return false
        return true
    }

    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0, to: Int = haystack.size): Int {
        if (needle.isEmpty()) return -1
        val end = minOf(to, haystack.size) - needle.size
        var i = maxOf(0, from)
        outer@ while (i <= end) {
            for (k in needle.indices) if (haystack[i + k] != needle[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    fun be16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun be32(v: Long): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
