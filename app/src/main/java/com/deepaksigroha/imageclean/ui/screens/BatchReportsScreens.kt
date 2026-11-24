package com.deepaksigroha.imageclean.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.deepaksigroha.imageclean.core.ai.AiVerdict
import com.deepaksigroha.imageclean.core.c2pa.C2paState
import com.deepaksigroha.imageclean.data.BatchWorker
import com.deepaksigroha.imageclean.data.MediaSaver
import com.deepaksigroha.imageclean.data.Report
import com.deepaksigroha.imageclean.data.ReportGenerator
import com.deepaksigroha.imageclean.data.ReportSection
import com.deepaksigroha.imageclean.data.db.RecordEntity
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.Dest
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.theme.StatusColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BatchScreen(vm: MainViewModel, act: AppActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by vm.batchWork.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var clean by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(BatchWorker.MAX_IMAGES)) { picked = it }

    val batchId = vm.currentBatchId(info)
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val batchRows = records.filter { it.batchId != null && it.batchId == batchId }.sortedBy { it.id }

    ScreenColumn {
        SectionCard("Select images", Icons.Default.Collections, subtitle = "Up to ${BatchWorker.MAX_IMAGES} images per batch") {
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !running) {
                Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text(if (picked.isEmpty()) "Choose images" else "${picked.size} selected – change")
            }
            SwitchRow("Also export privacy-cleaned copies", "Removes GPS, device, time, software and personal EXIF, thumbnails, XMP/IPTC (AI disclosures and C2PA are preserved)", clean) { clean = it }
            Text("Each image is checked for EXIF, IPTC, XMP, C2PA credentials, AI provenance, GPS and visible watermark regions, and gets its own report.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.startBatch(picked, clean) }, enabled = picked.isNotEmpty() && !running, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start batch analysis")
            }
        }

        info?.let { wi ->
            val done = wi.progress.getInt(BatchWorker.KEY_DONE, wi.outputData.getInt(BatchWorker.KEY_DONE, 0))
            val total = wi.progress.getInt(BatchWorker.KEY_TOTAL, maxOf(done, batchRows.size))
            SectionCard("Progress", Icons.Default.HourglassTop, subtitle = wi.state.name.lowercase().replaceFirstChar { it.uppercase() }) {
                if (running) {
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("$done of $total images processed in the background")
                    OutlinedButton(onClick = vm::cancelBatch) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Cancel") }
                } else if (wi.state == WorkInfo.State.CANCELLED) Text("Batch cancelled. ${batchRows.size} image(s) were processed before cancelling.")
                else if (wi.state == WorkInfo.State.SUCCEEDED) Text("Finished – ${batchRows.size} image(s), ${batchRows.count { !it.processedOk }} failed.")
                else if (wi.state == WorkInfo.State.FAILED) Text("The batch failed. Try again with fewer images.")
            }
        }

        if (batchRows.isNotEmpty()) {
            SectionCard("Results", Icons.Default.TableRows, subtitle = "Tap an image to open its report") {
                batchRows.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { vm.showReport(r); act.navigate(Dest.REPORTS) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(listOfNotNull("EXIF".takeIf { r.hasExif }, "GPS".takeIf { r.hasGps }, "XMP".takeIf { r.hasXmp }, "IPTC".takeIf { r.hasIptc }, "C2PA".takeIf { r.hasC2pa })
                                .joinToString(" · ").ifBlank { if (r.processedOk) "No metadata" else "Error" }, style = MaterialTheme.typography.bodySmall)
                        }
                        val v = runCatching { AiVerdict.valueOf(r.aiVerdict) }.getOrDefault(AiVerdict.UNDETERMINED)
                        StatusChip(shortVerdict(v), when (v) { AiVerdict.AI_GENERATED -> StatusColors.bad; AiVerdict.AI_ASSISTED -> StatusColors.warn
                            AiVerdict.C2PA_INVALID -> StatusColors.bad; AiVerdict.C2PA_VERIFIED_NO_AI -> StatusColors.good; else -> StatusColors.neutral })
                    }
                }
                Text("Consolidated report", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true, false).forEach { pdf ->
                        OutlinedButton(onClick = {
                            scope.launch {
                                val rows = vm.batchRecords(batchId!!)
                                val reports = listOf(summary(rows)) + rows.map { ReportGenerator.fromJson(it.reportJson) }
                                val (uri, mime) = vm.reportFile(reports, pdf, "ImageClean_${batchId}")
                                ctx.startActivity(MediaSaver.shareIntent(uri, mime))
                            }
                        }) { Text(if (pdf) "PDF" else "JSON") }
                    }
                }
            }
        }
    }
}

private fun shortVerdict(v: AiVerdict) = when (v) {
    AiVerdict.AI_GENERATED -> "AI-generated"; AiVerdict.AI_ASSISTED -> "AI-assisted"; AiVerdict.C2PA_VERIFIED_NO_AI -> "C2PA ✓"
    AiVerdict.C2PA_INVALID -> "C2PA ✗"; AiVerdict.NONE -> "No AI data"; AiVerdict.UNDETERMINED -> "Unknown"
}

private fun summary(rows: List<RecordEntity>) = Report("Batch summary", System.currentTimeMillis(), listOf(
    ReportSection("Totals", listOf(
        "Images" to rows.size.toString(),
        "With EXIF" to rows.count { it.hasExif }.toString(),
        "With GPS" to rows.count { it.hasGps }.toString(),
        "With XMP" to rows.count { it.hasXmp }.toString(),
        "With IPTC" to rows.count { it.hasIptc }.toString(),
        "With C2PA" to rows.count { it.hasC2pa }.toString(),
        "C2PA invalid" to rows.count { it.c2paState == C2paState.INVALID.name }.toString(),
        "AI provenance" to rows.count { it.aiVerdict == AiVerdict.AI_GENERATED.name || it.aiVerdict == AiVerdict.AI_ASSISTED.name }.toString(),
        "Failed" to rows.count { !it.processedOk }.toString(),
    )),
    ReportSection("Images", rows.map { it.fileName to "${it.format} · ${ReportGenerator.fmtSize(it.fileSize)} · ${it.aiVerdict} · C2PA ${it.c2paState}" +
        (it.outputName?.let { o -> " → $o" } ?: "") }),
))

@Composable
fun ReportsScreen(vm: MainViewModel, act: AppActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val report by vm.lastReport.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }

    ScreenColumn {
        val r = report
        if (r == null) {
            SectionCard("No report yet", Icons.Default.Description) {
                Text("Inspect, clean or export an image and its authenticity & modification report will appear here.")
                Button(onClick = act.pickImage) { Text("Select image") }
            }
        } else {
            SectionCard(r.title, Icons.Default.Description, subtitle = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.created))) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { val (u, m) = vm.reportFile(listOf(r), true, "ImageClean_report"); ctx.startActivity(MediaSaver.shareIntent(u, m)) } }) {
                        Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("PDF")
                    }
                    OutlinedButton(onClick = { scope.launch { val (u, m) = vm.reportFile(listOf(r), false, "ImageClean_report"); ctx.startActivity(MediaSaver.shareIntent(u, m)) } }) {
                        Icon(Icons.Default.DataObject, null); Spacer(Modifier.width(6.dp)); Text("JSON")
                    }
                }
            }
            r.sections.forEach { s ->
                SectionCard(s.title, collapsible = true, initiallyExpanded = s.title != "About this report") {
                    s.rows.forEach { (k, v) -> InfoRow(k, v) }
                }
            }
        }

        SectionCard("Modification history", Icons.Default.History, subtitle = "${records.size} entries stored on this device", collapsible = true, initiallyExpanded = showHistory) {
            if (records.isEmpty()) Text("No history yet.")
            records.take(200).forEach { rec ->
                Row(Modifier.fillMaxWidth().clickable { vm.showReport(rec) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rec.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(rec.timestamp)) + (rec.outputName?.let { " → $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    StatusChip(rec.operation.lowercase().replaceFirstChar { it.uppercase() }, StatusColors.info)
                }
            }
        }
        LaunchedEffect(Unit) { showHistory = report == null }
    }
}
