package com.deepaksigroha.imageclean.core.c2pa

import java.io.ByteArrayOutputStream

/** Minimal RFC 8949 CBOR decoder/encoder – enough for C2PA claims, assertions and COSE_Sign1. */
sealed class CborValue {
    data class UInt(val v: Long) : CborValue()
    data class NInt(val v: Long) : CborValue() // value = -1 - n, stored already negated
    class Bytes(val v: ByteArray) : CborValue() { override fun toString() = "h'${v.size} bytes'" }
    data class Text(val v: String) : CborValue()
    data class Arr(val v: List<CborValue>) : CborValue()
    data class Map(val v: LinkedHashMap<CborValue, CborValue>) : CborValue()
    data class Tag(val tag: Long, val v: CborValue) : CborValue()
    data class Simple(val v: Int) : CborValue()
    data class Bool(val v: Boolean) : CborValue()
    data object Null : CborValue()
    data object Undefined : CborValue()
    data class Float(val v: Double) : CborValue()

    fun asLong(): Long? = when (this) { is UInt -> v; is NInt -> v; else -> null }
    fun asText(): String? = (this as? Text)?.v
    fun asBytes(): ByteArray? = (this as? Bytes)?.v
    fun asArr(): List<CborValue>? = (this as? Arr)?.v ?: (this as? Tag)?.v?.asArr()
    fun asMap(): kotlin.collections.Map<CborValue, CborValue>? = (this as? Map)?.v ?: (this as? Tag)?.v?.asMap()
    operator fun get(key: String): CborValue? = asMap()?.get(Text(key))
    operator fun get(key: Long): CborValue? = asMap()?.let { m -> m[UInt(key)] ?: m[NInt(key)] }

    /** Human-readable rendering for the UI. */
    fun render(indent: String = "", maxLen: Int = 4000): String {
        val sb = StringBuilder()
        fun go(v: CborValue, ind: String) {
            if (sb.length > maxLen) return
            when (v) {
                is Map -> { sb.append("{\n"); v.v.forEach { (k, x) -> sb.append("$ind  ${k.short()}: "); go(x, "$ind  "); sb.append("\n") }; sb.append("$ind}") }
                is Arr -> { sb.append("[\n"); v.v.forEach { x -> sb.append("$ind  "); go(x, "$ind  "); sb.append("\n") }; sb.append("$ind]") }
                is Tag -> { sb.append("tag(${v.tag}) "); go(v.v, ind) }
                else -> sb.append(v.short())
            }
        }
        go(this, indent)
        return if (sb.length > maxLen) sb.substring(0, maxLen) + "…" else sb.toString()
    }

    fun short(): String = when (this) {
        is UInt -> v.toString(); is NInt -> v.toString(); is Text -> "\"$v\""; is Bytes -> "<${v.size} bytes>"
        is Bool -> v.toString(); Null -> "null"; Undefined -> "undefined"; is Float -> v.toString(); is Simple -> "simple($v)"
        is Arr -> "[${v.size} items]"; is Map -> "{${v.size} entries}"; is Tag -> "tag($tag)"
    }
}

class CborDecoder(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    val position get() = pos

    fun decode(depth: Int = 0): CborValue {
        require(depth < 64) { "CBOR nesting too deep" }
        require(pos < end) { "Unexpected end of CBOR" }
        val ib = b[pos++].toInt() and 0xFF
        val major = ib shr 5
        val info = ib and 0x1F
        if (major == 7) return decodeSimple(info)
        if (info == 31) return decodeIndefinite(major, depth)
        val arg = readArg(info)
        return when (major) {
            0 -> CborValue.UInt(arg)
            1 -> CborValue.NInt(-1 - arg)
            2 -> CborValue.Bytes(take(arg))
            3 -> CborValue.Text(String(take(arg), Charsets.UTF_8))
            4 -> { checkCount(arg); CborValue.Arr(List(arg.toInt()) { decode(depth + 1) }) }
            5 -> {
                checkCount(arg)
                val m = LinkedHashMap<CborValue, CborValue>()
                repeat(arg.toInt()) { val k = decode(depth + 1); m[k] = decode(depth + 1) }
                CborValue.Map(m)
            }
            6 -> CborValue.Tag(arg, decode(depth + 1))
            else -> error("bad major type")
        }
    }

    private fun checkCount(n: Long) = require(n in 0..(end - pos).toLong()) { "CBOR length out of range" }

    private fun decodeIndefinite(major: Int, depth: Int): CborValue = when (major) {
        2, 3 -> {
            val out = ByteArrayOutputStream()
            while (b[pos].toInt() and 0xFF != 0xFF) {
                val chunk = decode(depth + 1)
                out.write(chunk.asBytes() ?: (chunk.asText() ?: "").toByteArray())
            }
            pos++
            if (major == 2) CborValue.Bytes(out.toByteArray()) else CborValue.Text(out.toString(Charsets.UTF_8.name()))
        }
        4 -> {
            val l = ArrayList<CborValue>()
            while (b[pos].toInt() and 0xFF != 0xFF) l += decode(depth + 1)
            pos++; CborValue.Arr(l)
        }
        5 -> {
            val m = LinkedHashMap<CborValue, CborValue>()
            while (b[pos].toInt() and 0xFF != 0xFF) { val k = decode(depth + 1); m[k] = decode(depth + 1) }
            pos++; CborValue.Map(m)
        }
        else -> error("bad indefinite item")
    }

    private fun decodeSimple(info: Int): CborValue = when (info) {
        20 -> CborValue.Bool(false); 21 -> CborValue.Bool(true); 22 -> CborValue.Null; 23 -> CborValue.Undefined
        24 -> CborValue.Simple(b[pos++].toInt() and 0xFF)
        25 -> { val h = readArg(25).toInt(); CborValue.Float(halfToDouble(h)) }
        26 -> CborValue.Float(java.lang.Float.intBitsToFloat(readArg(26).toInt()).toDouble())
        27 -> CborValue.Float(java.lang.Double.longBitsToDouble(readArg(27)))
        else -> CborValue.Simple(info)
    }

    private fun readArg(info: Int): Long = when {
        info < 24 -> info.toLong()
        info == 24 -> (b[pos++].toLong() and 0xFF)
        info == 25 -> { val v = ((b[pos].toLong() and 0xFF) shl 8) or (b[pos + 1].toLong() and 0xFF); pos += 2; v }
        info == 26 -> { var v = 0L; repeat(4) { v = (v shl 8) or (b[pos++].toLong() and 0xFF) }; v }
        info == 27 -> { var v = 0L; repeat(8) { v = (v shl 8) or (b[pos++].toLong() and 0xFF) }; v }
        else -> error("bad CBOR argument")
    }

    private fun take(n: Long): ByteArray {
        require(n >= 0 && pos + n <= end) { "CBOR string overruns buffer" }
        val r = b.copyOfRange(pos, pos + n.toInt()); pos += n.toInt(); return r
    }

    private fun halfToDouble(h: Int): Double {
        val exp = (h shr 10) and 0x1F; val mant = h and 0x3FF
        val v = when (exp) { 0 -> mant * Math.pow(2.0, -24.0); 31 -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> (mant + 1024) * Math.pow(2.0, exp - 25.0) }
        return if (h and 0x8000 != 0) -v else v
    }

    companion object {
        fun decode(bytes: ByteArray): CborValue = CborDecoder(bytes).decode()
    }
}

object CborEncoder {
    fun encode(v: CborValue): ByteArray = ByteArrayOutputStream().also { write(it, v) }.toByteArray()

    private fun head(out: ByteArrayOutputStream, major: Int, arg: Long) {
        val m = major shl 5
        when {
            arg < 24 -> out.write(m or arg.toInt())
            arg < 0x100 -> { out.write(m or 24); out.write(arg.toInt()) }
            arg < 0x10000 -> { out.write(m or 25); out.write((arg shr 8).toInt()); out.write(arg.toInt()) }
            arg < 0x100000000L -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write((arg shr s).toInt()) }
            else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write((arg shr s).toInt()) }
        }
    }

    private fun write(out: ByteArrayOutputStream, v: CborValue) {
        when (v) {
            is CborValue.UInt -> head(out, 0, v.v)
            is CborValue.NInt -> head(out, 1, -1 - v.v)
            is CborValue.Bytes -> { head(out, 2, v.v.size.toLong()); out.write(v.v) }
            is CborValue.Text -> { val t = v.v.toByteArray(Charsets.UTF_8); head(out, 3, t.size.toLong()); out.write(t) }
            is CborValue.Arr -> { head(out, 4, v.v.size.toLong()); v.v.forEach { write(out, it) } }
            is CborValue.Map -> { head(out, 5, v.v.size.toLong()); v.v.forEach { (k, x) -> write(out, k); write(out, x) } }
            is CborValue.Tag -> { head(out, 6, v.tag); write(out, v.v) }
            is CborValue.Bool -> out.write(if (v.v) 0xF5 else 0xF4)
            CborValue.Null -> out.write(0xF6)
            CborValue.Undefined -> out.write(0xF7)
            is CborValue.Simple -> head(out, 7, v.v.toLong())
            is CborValue.Float -> { out.write(0xFB); val l = java.lang.Double.doubleToLongBits(v.v); for (s in 56 downTo 0 step 8) out.write((l shr s).toInt()) }
        }
    }
}
