package com.deepaksigroha.imageclean.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.core.model.ImageFormat
import com.deepaksigroha.imageclean.data.CleanOptions
import com.deepaksigroha.imageclean.data.ExifReader
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.Dest
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.theme.StatusColors
import com.deepaksigroha.imageclean.ui.value

@Composable
fun CleanerScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)

    val removable = a.exif.fields.filter { it.tag !in ExifReader.STRUCTURAL && it.tag != "__thumbnail" }
    var selected by remember(a) { mutableStateOf(removable.filter { it.category.privacySensitive }.map { it.tag }.toSet()) }
    var thumb by remember(a) { mutableStateOf(a.exif.hasThumbnail) }
    var xmp by remember(a) { mutableStateOf(false) }
    var iptc by remember(a) { mutableStateOf(false) }
    var comments by remember(a) { mutableStateOf(a.container.comments.isNotEmpty()) }
    var pngText by remember(a) { mutableStateOf(false) }
    var showPreview by remember(a) { mutableStateOf(false) }
    val disclosure = remember(a) { a.ai.evidence.isNotEmpty() }

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)

        SectionCard("EXIF fields", Icons.Default.Tune, subtitle = "${selected.size} of ${removable.size} selected for removal") {
            if (removable.isEmpty()) Text("This image has no removable EXIF fields.")
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { selected = removable.filter { it.category.privacySensitive }.map { it.tag }.toSet() }, label = { Text("All privacy-sensitive") })
                    AssistChip(onClick = { selected = removable.map { it.tag }.toSet() }, label = { Text("All") })
                    AssistChip(onClick = { selected = emptySet() }, label = { Text("None") })
                }
                removable.groupBy { it.category }.forEach { (cat, fields) ->
                    Text(cat.label + if (cat.privacySensitive) " · sensitive" else "", style = MaterialTheme.typography.labelLarge,
                        color = if (cat.privacySensitive) StatusColors.warn else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    fields.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (f.tag in selected) selected - f.tag else selected + f.tag },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = f.tag in selected, onCheckedChange = { selected = if (it) selected + f.tag else selected - f.tag })
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = MaterialTheme.typography.bodyMedium)
                                Text(f.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (a.exif.fields.any { it.tag in ExifReader.STRUCTURAL })
                Note("Orientation, dimensions and colour space are always kept so the picture displays correctly.", Icons.Default.Lock)
        }

        SectionCard("Other metadata", Icons.Default.Layers) {
            SwitchRow("Embedded thumbnail", if (a.exif.hasThumbnail) "A small preview copy stored inside EXIF – it can show the image before edits/crops" else "Not present",
                thumb, enabled = a.exif.hasThumbnail && a.format in setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.HEIF, ImageFormat.AVIF)) { thumb = it }
            SwitchRow("XMP packet", if (a.container.hasXmp) "Editing history, creator tool, descriptions" else "Not present", xmp,
                enabled = a.container.hasXmp && a.format != ImageFormat.WEBP) { xmp = it }
            SwitchRow("IPTC block", if (a.container.hasIptc) "Creator, copyright, captions, keywords" else "Not present", iptc, enabled = a.container.hasIptc) { iptc = it }
            SwitchRow("Comments", if (a.container.comments.isNotEmpty()) "JPEG comment segments" else "Not present", comments, enabled = a.container.comments.isNotEmpty()) { comments = it }
            SwitchRow("PNG text chunks", if (a.container.textChunks.isNotEmpty()) a.container.textChunks.keys.joinToString() else "Not present", pngText,
                enabled = a.container.textChunks.isNotEmpty()) { pngText = it }
        }

        SectionCard("Always preserved", Icons.Default.Lock) {
            if (a.container.hasC2pa) Bullet("C2PA Content Credentials – signed provenance is never altered or removed.", StatusColors.good)
            if (disclosure) Bullet("AI disclosure – if this image declares AI generation, an IPTC DigitalSourceType disclosure is kept or re-written.", StatusColors.good)
            Bullet("Image pixels and colour profile (JPEG/PNG are edited losslessly, no recompression).", StatusColors.good)
            Bullet("Your original file – a new copy is always created.", StatusColors.good)
            when (a.format) {
                ImageFormat.HEIF, ImageFormat.AVIF -> Note("${a.format.label} files cannot be edited in place on Android. The cleaned copy will be a high-quality JPEG (q95).", Icons.Default.WarningAmber, StatusColors.warn)
                ImageFormat.WEBP -> Note("For WebP, individual EXIF tags can be removed; XMP and thumbnail removal are not supported.", Icons.Default.Info)
                else -> {}
            }
            if (a.container.hasC2pa) Note("Changing any byte of a C2PA-signed file makes its content-binding check fail. The manifest stays, so its claims remain visible, but verifiers will report the copy as modified.",
                Icons.Default.WarningAmber, StatusColors.warn)
        }

        OutlinedButton(onClick = { showPreview = !showPreview }, Modifier.fillMaxWidth()) { Text(if (showPreview) "Hide preview of changes" else "Preview changes") }
        if (showPreview) SectionCard("Preview of changes", Icons.Default.Visibility) {
            val list = removable.filter { it.tag in selected }.map { "EXIF ${it.label}: ${it.value}" } +
                listOfNotNull(
                    "Embedded thumbnail".takeIf { thumb && a.exif.hasThumbnail },
                    "XMP packet".takeIf { xmp }, "IPTC block".takeIf { iptc }, "Comments".takeIf { comments }, "PNG text chunks".takeIf { pngText },
                )
            if (list.isEmpty()) Text("Nothing selected – the copy would be identical.")
            list.forEach { Bullet(it, StatusColors.bad) }
            Text("Will be kept: " + (removable.filter { it.tag !in selected }.joinToString { it.label }.ifBlank { "no other EXIF fields" }),
                style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { vm.clean(CleanOptions(selected, thumb, xmp, iptc, comments, pngText)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = output !is com.deepaksigroha.imageclean.ui.Ui.Loading,
        ) { Icon(Icons.Default.CleaningServices, null); Spacer(Modifier.width(8.dp)); Text("Export cleaned copy") }

        OutputCard(output, onViewReport = { act.navigate(Dest.REPORTS) }, onDismiss = vm::clearOutput)
    }
}
