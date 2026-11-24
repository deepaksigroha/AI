package com.deepaksigroha.imageclean.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.core.model.ExifCategory
import com.deepaksigroha.imageclean.data.ReportGenerator
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.theme.StatusColors
import com.deepaksigroha.imageclean.ui.value

@Composable
fun InspectorScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)

        SectionCard("File information", Icons.Default.InsertDriveFile) {
            InfoRow("File name", a.name)
            InfoRow("Format", a.format.label)
            InfoRow("File size", ReportGenerator.fmtSize(a.size))
            InfoRow("Dimensions", "${a.width} × ${a.height} px")
            InfoRow("Colour profile", a.container.iccDescription ?: if (a.container.hasIcc) "Embedded (unnamed)" else "None – sRGB assumed")
            InfoRow("Embedded thumbnail", if (a.exif.hasThumbnail) "Yes (${a.exif.thumbnailBytes} bytes)" else "No")
            InfoRow("Metadata present", a.metadataStatus)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("EXIF", if (a.hasExif) StatusColors.info else StatusColors.neutral)
                StatusChip("GPS", if (a.hasGps) StatusColors.bad else StatusColors.neutral)
                StatusChip("XMP", if (a.container.hasXmp) StatusColors.info else StatusColors.neutral)
                StatusChip("IPTC", if (a.container.hasIptc) StatusColors.info else StatusColors.neutral)
                StatusChip("C2PA", if (a.container.hasC2pa) StatusColors.good else StatusColors.neutral)
            }
        }

        if (a.hasGps || a.image.locationMayBeRedacted) SectionCard("Location", Icons.Default.LocationOn) {
            val ll = a.exif.latLong
            if (ll != null) {
                InfoRow("Coordinates", "%.6f, %.6f".format(ll[0], ll[1]), mono = true, valueColor = StatusColors.bad)
                Note("Anyone who receives this file can see where it was taken.", Icons.Default.WarningAmber, StatusColors.warn)
            } else if (a.hasGps) InfoRow("GPS tags", "Present")
            if (a.image.locationMayBeRedacted && ll == null)
                Note("Android may have hidden GPS data from this app. Use “Open file” to read the original file if you need to check location.")
        }

        val groups = a.exif.fields.groupBy { it.category }
        if (groups.isEmpty()) SectionCard("EXIF metadata", Icons.Default.CameraAlt) { Text("No EXIF metadata found.") }
        ExifCategory.entries.forEach { cat ->
            val rows = groups[cat] ?: return@forEach
            SectionCard("EXIF – ${cat.label}", Icons.Default.CameraAlt, subtitle = if (cat.privacySensitive) "Privacy-sensitive" else null,
                collapsible = true, initiallyExpanded = cat != ExifCategory.IMAGE) {
                rows.forEach { InfoRow(it.label, it.value, mono = cat == ExifCategory.LOCATION) }
            }
        }

        SectionCard("IPTC metadata", Icons.Default.Article, collapsible = true) {
            val i = a.iptc
            val xp = a.xmp?.properties.orEmpty()
            val creator = i?.creator?.joinToString().takeUnless { it.isNullOrBlank() } ?: a.xmp?.creators?.joinToString()?.takeIf { it.isNotBlank() }
            InfoRow("Creator", creator ?: "—")
            InfoRow("Copyright", i?.copyright ?: a.xmp?.rights ?: "—")
            InfoRow("Description", i?.description ?: a.xmp?.description ?: "—")
            InfoRow("Keywords", i?.keywords?.joinToString().takeUnless { it.isNullOrBlank() } ?: xp["dc:subject"] ?: "—")
            InfoRow("Digital source type", a.xmp?.digitalSourceType?.substringAfterLast('/') ?: "—")
            i?.raw?.filterKeys { it !in setOf("By-line (Creator)", "Copyright notice", "Caption / Description", "Keywords") }?.forEach { (k, v) -> InfoRow(k, v) }
            if (i == null && a.xmp == null) Text("No IPTC data found.", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("XMP metadata", Icons.Default.Code, collapsible = true, initiallyExpanded = a.xmp != null) {
            val x = a.xmp
            if (x == null) Text("No XMP packet found.")
            else {
                InfoRow("Creator tool", x.creatorTool ?: "—")
                if (x.history.isNotEmpty()) {
                    Text("Editing history", style = MaterialTheme.typography.labelLarge)
                    x.history.take(30).forEach { Bullet(it) }
                }
                if (x.aiHints.isNotEmpty()) {
                    Text("AI-related properties", style = MaterialTheme.typography.labelLarge, color = StatusColors.warn)
                    x.aiHints.forEach { Bullet(it, StatusColors.warn) }
                }
                if (x.provenanceRefs.isNotEmpty()) {
                    Text("Embedded provenance references", style = MaterialTheme.typography.labelLarge)
                    x.provenanceRefs.forEach { Bullet(it) }
                }
                var showAll by remember { mutableStateOf(false) }
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide all properties" else "Show all ${x.properties.size} properties") }
                if (showAll) x.properties.forEach { (k, v) -> InfoRow(k, v.take(400), mono = true) }
            }
        }

        if (a.container.textChunks.isNotEmpty()) SectionCard("PNG text chunks", Icons.Default.Notes, collapsible = true) {
            a.container.textChunks.forEach { (k, v) -> InfoRow(k, v.take(600), mono = true) }
        }
        if (a.container.comments.isNotEmpty()) SectionCard("Comments", Icons.Default.Comment) { a.container.comments.forEach { Bullet(it) } }

        SectionCard("File structure", Icons.Default.AccountTree, collapsible = true, initiallyExpanded = false) {
            a.container.blocks.forEach { b ->
                Text("• ${b.kind.label}" + (if (b.length > 0) " – ${ReportGenerator.fmtSize(b.length.toLong())}" else "") + (if (b.note.isNotBlank()) " (${b.note})" else ""),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            a.container.warnings.forEach { Note(it, Icons.Default.WarningAmber, StatusColors.warn) }
        }
    }
}
