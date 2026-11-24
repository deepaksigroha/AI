package com.deepaksigroha.imageclean.core.meta

import com.deepaksigroha.imageclean.core.Bytes
import com.deepaksigroha.imageclean.core.model.IptcData
import com.deepaksigroha.imageclean.core.model.XmpData

/** IPTC-IIM (Information Interchange Model) record parser. */
object IptcParser {
    private val names = mapOf(
        5 to "Object name", 25 to "Keywords", 40 to "Special instructions", 55 to "Date created",
        60 to "Time created", 65 to "Originating program", 70 to "Program version",
        80 to "By-line (Creator)", 85 to "By-line title", 90 to "City", 95 to "Province/State",
        101 to "Country", 105 to "Headline", 110 to "Credit", 115 to "Source", 116 to "Copyright notice",
        120 to "Caption / Description", 122 to "Caption writer",
    )

    fun parse(iim: ByteArray): IptcData {
        val values = LinkedHashMap<Int, MutableList<String>>()
        var utf8 = false
        var i = 0
        while (i + 5 <= iim.size) {
            if (Bytes.u8(iim, i) != 0x1C) { i++; continue }
            val record = Bytes.u8(iim, i + 1)
            val ds = Bytes.u8(iim, i + 2)
            var len = Bytes.u16be(iim, i + 3)
            var p = i + 5
            if (len and 0x8000 != 0) { // extended length
                val n = len and 0x7FFF
                len = 0
                for (k in 0 until n) len = (len shl 8) or Bytes.u8(iim, p + k)
                p += n
            }
            if (p + len > iim.size) break
            if (record == 1 && ds == 90) {
                // Coded character set: ESC % G => UTF-8
                utf8 = len >= 3 && iim[p] == 0x1B.toByte() && iim[p + 1] == '%'.code.toByte() && iim[p + 2] == 'G'.code.toByte()
            }
            if (record == 2) {
                val s = String(iim, p, len, if (utf8) Charsets.UTF_8 else Charsets.UTF_8).trim()
                values.getOrPut(ds) { ArrayList() } += s
            }
            i = p + len
        }
        fun one(ds: Int) = values[ds]?.firstOrNull()
        val raw = LinkedHashMap<String, String>()
        values.forEach { (k, v) -> raw[names[k] ?: "Dataset 2:$k"] = v.joinToString("; ") }
        return IptcData(
            creator = values[80].orEmpty(), copyright = one(116), description = one(120),
            keywords = values[25].orEmpty(), headline = one(105), credit = one(110), source = one(115),
            city = one(90), country = one(101), raw = raw,
        )
    }
}

/**
 * Lightweight XMP reader. XMP is RDF/XML; properties may be written either as attributes
 * (ns:prop="value") or as elements (<ns:prop>value</ns:prop>, rdf:Seq/Bag/Alt lists).
 * We extract both forms with tolerant regular expressions – robust against the many
 * dialects written by cameras, editors and AI generators.
 */
object XmpParser {
    private val attrRe = Regex("""([A-Za-z][\w.\-]*:[A-Za-z][\w.\-]*)\s*=\s*"([^"]*)"""")
    private val simpleElRe = Regex("""<([A-Za-z][\w.\-]*:[A-Za-z][\w.\-]*)(?:\s[^>]*)?>([^<]+)</\1>""")
    private val listElRe = Regex("""<([A-Za-z][\w.\-]*:[A-Za-z][\w.\-]*)(?:\s[^>]*)?>\s*<rdf:(?:Seq|Bag|Alt)[^>]*>(.*?)</rdf:(?:Seq|Bag|Alt)>\s*</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val liRe = Regex("""<rdf:li(?:\s[^>]*)?>([^<]*)</rdf:li>""")
    private val resourceRe = Regex("""<([A-Za-z][\w.\-]*:[A-Za-z][\w.\-]*)\s+rdf:resource\s*=\s*"([^"]*)"""")
    private val historyAgentRe = Regex("""stEvt:softwareAgent\s*=\s*"([^"]*)"|<stEvt:softwareAgent>([^<]*)</stEvt:softwareAgent>""")
    private val historyActionRe = Regex("""stEvt:action\s*=\s*"([^"]*)"|<stEvt:action>([^<]*)</stEvt:action>""")
    private val photoshopHistoryRe = Regex("""<photoshop:History>([^<]*)</photoshop:History>|photoshop:History\s*=\s*"([^"]*)"""")

    private val ignoredPrefixes = setOf("xmlns", "rdf", "x", "xml")

    fun parse(packets: List<String>): XmpData {
        val props = LinkedHashMap<String, String>()
        for (xml in packets) {
            attrRe.findAll(xml).forEach { m ->
                val k = m.groupValues[1]
                if (k.substringBefore(':') !in ignoredPrefixes) props.putIfAbsent(k, unescape(m.groupValues[2]))
            }
            simpleElRe.findAll(xml).forEach { m ->
                val k = m.groupValues[1]
                if (k.substringBefore(':') !in ignoredPrefixes) props.putIfAbsent(k, unescape(m.groupValues[2].trim()))
            }
            listElRe.findAll(xml).forEach { m ->
                val items = liRe.findAll(m.groupValues[2]).map { unescape(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
                if (items.isNotEmpty()) props[m.groupValues[1]] = items.joinToString("; ")
            }
            resourceRe.findAll(xml).forEach { m -> props.putIfAbsent(m.groupValues[1], unescape(m.groupValues[2])) }
        }
        val all = packets.joinToString("\n")
        val history = ArrayList<String>()
        val actions = historyActionRe.findAll(all).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        val agents = historyAgentRe.findAll(all).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        for (k in 0 until maxOf(actions.size, agents.size)) {
            val a = actions.getOrNull(k).orEmpty(); val s = agents.getOrNull(k).orEmpty()
            history += listOf(a, s).filter { it.isNotBlank() }.joinToString(" — ")
        }
        photoshopHistoryRe.findAll(all).forEach { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotBlank() }?.let { history += it } }

        val dst = props.entries.firstOrNull { it.key.endsWith(":DigitalSourceType", ignoreCase = true) }?.value
        val creatorTool = props["xmp:CreatorTool"] ?: props["xap:CreatorTool"]
        val aiHints = ArrayList<String>()
        props.forEach { (k, v) ->
            val lk = k.lowercase()
            if (AiPatterns.isAiPropertyKey(lk)) aiHints += "$k = ${v.take(120)}"
            AiPatterns.matchGenerator(v)?.let { aiHints += "$k mentions $it" }
            AiPatterns.matchSourceType(v)?.let { aiHints += "$k = ${it.label}" }
        }
        val prov = ArrayList<String>()
        props.forEach { (k, v) ->
            if (k.startsWith("c2pa:", true) || k.contains("provenance", true) || v.contains("c2pa", true) ||
                v.contains("contentcredentials", true) || k.equals("dcterms:provenance", true)
            ) prov += "$k = ${v.take(160)}"
        }
        return XmpData(
            properties = props,
            creatorTool = creatorTool,
            digitalSourceType = dst,
            creators = props["dc:creator"]?.split("; ").orEmpty(),
            rights = props["dc:rights"],
            description = props["dc:description"],
            history = history.distinct(),
            aiHints = aiHints.distinct(),
            provenanceRefs = prov.distinct(),
        )
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&#xA;", "\n").replace("&#10;", "\n").replace("&amp;", "&")
}
