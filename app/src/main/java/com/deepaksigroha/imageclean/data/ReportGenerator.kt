package com.deepaksigroha.imageclean.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.deepaksigroha.imageclean.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportSection(val title: String, val rows: List<Pair<String, String>>)
data class Report(val title: String, val created: Long, val sections: List<ReportSection>)

/** Information about an exported/cleaned copy, used for before/after reports. */
data class OutputInfo(
    val operation: String,
    val fileName: String,
    val analysis: ImageAnalysis?,
    val removed: List<String>,
    val retained: List<String>,
    val modified: List<String>,
    val compression: String,
    val warnings: List<String>,
)

object ReportGenerator {

    fun fmtSize(b: Long): String = when {
        b >= 1024 * 1024 -> "%.2f MB".format(b / 1048576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }

    private fun yn(b: Boolean) = if (b) "Present" else "Not present"

    fun originalSection(a: ImageAnalysis, title: String = "Original image") = ReportSection(title, listOf(
        "Filename" to a.name,
        "File format" to a.format.label,
        "File size" to fmtSize(a.size),
        "Dimensions" to "${a.width} × ${a.height}",
        "EXIF status" to yn(a.hasExif),
        "GPS status" to (a.exif.latLong?.let { "Present (%.5f, %.5f)".format(it[0], it[1]) } ?: if (a.hasGps) "Present" else "Not present"),
        "IPTC status" to yn(a.container.hasIptc),
        "XMP status" to yn(a.container.hasXmp),
        "C2PA status" to a.c2pa.state.title,
        "AI provenance" to a.ai.verdict.title,
        "Colour profile" to (a.container.iccDescription ?: if (a.container.hasIcc) "Embedded" else "None (sRGB assumed)"),
        "Metadata summary" to a.metadataStatus,
    ))

    fun build(a: ImageAnalysis, out: OutputInfo? = null): Report {
        val sections = ArrayList<ReportSection>()
        sections += originalSection(a)
        sections += ReportSection("AI provenance evidence", listOf("Verdict" to a.ai.verdict.title, "Explanation" to a.ai.explanation) +
            a.ai.evidence.map { "${it.source} [${it.kind.label}]" to it.finding })
        if (a.c2pa.state != com.deepaksigroha.imageclean.core.c2pa.C2paState.NONE) {
            sections += ReportSection("C2PA validation", listOf("State" to a.c2pa.state.title, "Summary" to a.c2pa.summary) +
                a.c2pa.checks.map { it.name to "${it.result}: ${it.detail}" } +
                a.c2pa.manifests.flatMap { m -> listOfNotNull(
                    "Manifest" to (m.label + if (m.isActive) " (active)" else ""),
                    m.claimGenerator?.let { "Claim generator" to it },
                    m.signerSubject?.let { "Signer" to it },
                    m.signerIssuer?.let { "Issuer" to it },
                ) + m.actions.map { "Action" to listOfNotNull(it.action, it.softwareAgent, it.digitalSourceType?.substringAfterLast('/')).joinToString(" · ") } })
        }
        if (out != null) {
            val o = out.analysis
            sections += ReportSection("Exported image", listOfNotNull(
                "Operation" to out.operation,
                "Exported filename" to out.fileName,
                o?.let { "File format" to it.format.label },
                o?.let { "File size" to fmtSize(it.size) },
                o?.let { "Dimensions" to "${it.width} × ${it.height}" },
                "Image compression" to out.compression,
                o?.let { "C2PA verification status" to it.c2pa.state.title },
                o?.let { "AI provenance after export" to it.ai.verdict.title },
            ))
            sections += ReportSection("Metadata removed", out.removed.map { "Removed" to it }.ifEmpty { listOf("Removed" to "Nothing") })
            sections += ReportSection("Metadata retained", out.retained.map { "Retained" to it }.ifEmpty { listOf("Retained" to "Nothing") })
            sections += ReportSection("Metadata modified", out.modified.map { "Modified" to it }.ifEmpty { listOf("Modified" to "Nothing") })
            if (out.warnings.isNotEmpty()) sections += ReportSection("Warnings", out.warnings.map { "Note" to it })
            if (o != null) {
                sections += ReportSection("Before → after", listOf(
                    "Size" to "${fmtSize(a.size)} → ${fmtSize(o.size)}",
                    "Dimensions" to "${a.width}×${a.height} → ${o.width}×${o.height}",
                    "EXIF" to "${yn(a.hasExif)} → ${yn(o.hasExif)}",
                    "GPS" to "${yn(a.hasGps)} → ${yn(o.hasGps)}",
                    "XMP" to "${yn(a.container.hasXmp)} → ${yn(o.container.hasXmp)}",
                    "IPTC" to "${yn(a.container.hasIptc)} → ${yn(o.container.hasIptc)}",
                    "C2PA" to "${a.c2pa.state.title} → ${o.c2pa.state.title}",
                    "AI provenance" to "${a.ai.verdict.title} → ${o.ai.verdict.title}",
                ))
            }
        }
        sections += ReportSection("About this report", listOf(
            "Generated by" to "ImageClean Pro ${BuildConfig.VERSION_NAME} – Developed by Deepak Sigroha",
            "Processing" to "All analysis performed locally on the device; no data was uploaded.",
            "Note" to "Absence of AI metadata does not prove that an image is human-generated.",
        ))
        return Report("Image Authenticity & Modification Report", System.currentTimeMillis(), sections)
    }

    fun toJson(r: Report): JSONObject = JSONObject().apply {
        put("title", r.title)
        put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(r.created)))
        put("generator", "ImageClean Pro ${BuildConfig.VERSION_NAME} (Deepak Sigroha)")
        val secs = JSONArray()
        r.sections.forEach { s ->
            val rows = JSONArray()
            s.rows.forEach { (k, v) -> rows.put(JSONObject().put("field", k).put("value", v)) }
            secs.put(JSONObject().put("section", s.title).put("rows", rows))
        }
        put("sections", secs)
    }

    fun fromJson(json: String): Report = runCatching {
        val o = JSONObject(json)
        val secs = o.getJSONArray("sections")
        Report(o.optString("title"), System.currentTimeMillis(), (0 until secs.length()).map { i ->
            val s = secs.getJSONObject(i); val rows = s.getJSONArray("rows")
            ReportSection(s.getString("section"), (0 until rows.length()).map { j -> rows.getJSONObject(j).let { it.getString("field") to it.getString("value") } })
        })
    }.getOrElse { Report("Report", System.currentTimeMillis(), listOf(ReportSection("Error", listOf("Report" to "Could not read stored report")))) }

    /** Renders one or more reports into a paginated A4 PDF using Android's built-in PdfDocument. */
    fun toPdf(reports: List<Report>, docTitle: String): ByteArray {
        val doc = PdfDocument()
        val pw = 595; val ph = 842; val margin = 40f
        val title = Paint().apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(30, 58, 138); isAntiAlias = true }
        val head = Paint().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK; isAntiAlias = true }
        val key = Paint().apply { textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.DKGRAY; isAntiAlias = true }
        val body = Paint().apply { textSize = 9.5f; color = Color.BLACK; isAntiAlias = true }
        val foot = Paint().apply { textSize = 8f; color = Color.GRAY; isAntiAlias = true }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            y = margin
            page!!.canvas.drawText("ImageClean Pro – $docTitle", margin, ph - 20f, foot)
            page!!.canvas.drawText("Developed by Deepak Sigroha · page $pageNo", pw - margin - 170f, ph - 20f, foot)
        }
        fun ensure(h: Float) { if (page == null || y + h > ph - 50f) newPage() }
        fun wrap(text: String, p: Paint, width: Float): List<String> {
            val lines = ArrayList<String>()
            text.split("\n").forEach { para ->
                var line = ""
                para.split(" ").forEach { word ->
                    val t = if (line.isEmpty()) word else "$line $word"
                    if (p.measureText(t) > width && line.isNotEmpty()) { lines += line; line = word } else line = t
                    while (p.measureText(line) > width && line.length > 10) {
                        var cut = line.length - 1
                        while (cut > 1 && p.measureText(line.substring(0, cut)) > width) cut--
                        lines += line.substring(0, cut); line = line.substring(cut)
                    }
                }
                lines += line
            }
            return lines
        }
        reports.forEachIndexed { idx, r ->
            if (idx > 0) { ensure(80f); y += 10f }
            ensure(40f)
            page!!.canvas.drawText(r.title, margin, y + 14f, title); y += 22f
            page!!.canvas.drawText(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.created)), margin, y + 10f, foot); y += 18f
            r.sections.forEach { s ->
                ensure(30f)
                y += 6f; page!!.canvas.drawText(s.title, margin, y + 12f, head); y += 18f
                s.rows.forEach { (k, v) ->
                    val keyLines = wrap(k, key, 150f); val valLines = wrap(v, body, pw - 2 * margin - 160f)
                    val h = maxOf(keyLines.size, valLines.size) * 12f + 3f
                    ensure(h)
                    keyLines.forEachIndexed { i, l -> page!!.canvas.drawText(l, margin, y + 10f + i * 12f, key) }
                    valLines.forEachIndexed { i, l -> page!!.canvas.drawText(l, margin + 160f, y + 10f + i * 12f, body) }
                    y += h
                }
            }
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
