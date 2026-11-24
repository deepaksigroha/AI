package com.deepaksigroha.imageclean.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.core.ai.IgAssessment
import com.deepaksigroha.imageclean.data.ExportOptions
import com.deepaksigroha.imageclean.data.ExportPreset
import com.deepaksigroha.imageclean.data.SocialExporter
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.Dest
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.Ui
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.theme.StatusColors
import com.deepaksigroha.imageclean.ui.value
import kotlinx.coroutines.delay

@Composable
fun InstagramScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val ig by vm.instagram.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)
        val r = ig
        if (r == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing compatibility report…"); return@ScreenColumn }
        val color = when (r.assessment) { IgAssessment.LOW -> StatusColors.good; IgAssessment.SOME -> StatusColors.warn; IgAssessment.STRONG -> StatusColors.bad }
        StatusBanner(r.assessment.title, r.assessment.description, color, Icons.Default.PhotoCamera)

        SectionCard("AI provenance indicators", Icons.Default.AutoAwesome) {
            if (r.aiIndicators.isEmpty()) Text("None found in metadata.") else r.aiIndicators.forEach { Bullet(it, StatusColors.warn) }
        }
        SectionCard("Potential AI disclosure indicators", Icons.Default.Label) {
            if (r.disclosureIndicators.isEmpty()) Text("No standard disclosure (C2PA or IPTC Digital Source Type) is embedded.")
            else r.disclosureIndicators.forEach { Bullet(it) }
        }
        SectionCard("Metadata privacy concerns", Icons.Default.PrivacyTip) {
            if (r.privacyConcerns.isEmpty()) Text("No privacy-sensitive EXIF found.") else r.privacyConcerns.forEach { Bullet(it, StatusColors.bad) }
            if (r.privacyConcerns.isNotEmpty()) TextButton(onClick = { act.navigate(Dest.CLEANER) }) { Text("Open Metadata Cleaner") }
        }
        SectionCard("Format & encoding", Icons.Default.Image) {
            if (r.formatNotes.isEmpty()) Text("Format and size are suitable for Instagram.") else r.formatNotes.forEach { Bullet(it) }
        }
        SectionCard("Recommended export settings", Icons.Default.Tune) {
            r.recommendations.forEach { Bullet(it, StatusColors.good) }
            TextButton(onClick = { act.navigate(Dest.EXPORT) }) { Text("Open Social Media Export") }
        }
        StatusBanner("Important", r.disclaimer, StatusColors.info, Icons.Default.Info)
    }
}

@Composable
fun ExportScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val exportPreview by vm.exportPreview.collectAsStateWithLifecycle()
    val custom by vm.presets.collectAsStateWithLifecycle()
    val defaultQ by vm.prefs.defaultQuality.collectAsStateWithLifecycle()
    val keepCreditPref by vm.prefs.keepCredit.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)

    val all = ExportPreset.BUILT_IN + custom.map { ExportPreset(it.name, it.aspectW, it.aspectH, it.maxLongEdge, it.png, it.quality, custom = true, id = it.id) }
    var preset by remember { mutableStateOf(ExportPreset.BUILT_IN.first()) }
    var quality by remember(defaultQ) { mutableIntStateOf(defaultQ) }
    var png by remember { mutableStateOf(false) }
    var offX by remember(a) { mutableFloatStateOf(0.5f) }
    var offY by remember(a) { mutableFloatStateOf(0.5f) }
    var keepCredit by remember(keepCreditPref) { mutableStateOf(keepCreditPref) }
    var showSave by remember { mutableStateOf(false) }
    val effective = preset.copy(quality = quality, png = png)
    val opts = ExportOptions(effective, keepCredit, offX, offY)

    LaunchedEffect(a, effective.aspectW, effective.aspectH, effective.maxLongEdge, offX, offY) { delay(150); vm.previewExport(opts) }

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)
        SectionCard("Platform preset", Icons.Default.Dashboard) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEach { p -> FilterChip(selected = p == preset, onClick = { preset = p; png = p.png; quality = p.quality }, label = { Text(p.name) }) }
            }
            val (cw, ch) = SocialExporter.cropSize(a.width, a.height, preset)
            val scale = minOf(1.0, preset.maxLongEdge.toDouble() / maxOf(cw, ch))
            InfoRow("Aspect ratio", preset.ratioLabel)
            InfoRow("Output size", "${(cw * scale).toInt()} × ${(ch * scale).toInt()} px")
            if (preset.custom) custom.firstOrNull { it.id == preset.id }?.let { e ->
                TextButton(onClick = { vm.deletePreset(e); preset = ExportPreset.BUILT_IN.first() }) { Text("Delete this custom preset") }
            }
        }
        exportPreview?.let { bmp ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Image(bmp, "Export preview", Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height))
            }
        }
        SectionCard("Adjust", Icons.Default.Tune) {
            if (preset.aspectW != 0) {
                Text("Crop position – horizontal", style = MaterialTheme.typography.labelLarge)
                Slider(offX, { offX = it })
                Text("Crop position – vertical", style = MaterialTheme.typography.labelLarge)
                Slider(offY, { offY = it })
            }
            Row {
                FilterChip(selected = !png, onClick = { png = false }, label = { Text("JPEG") }); Spacer(Modifier.width(8.dp))
                FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
            }
            if (!png) {
                Text("JPEG quality: $quality", style = MaterialTheme.typography.labelLarge)
                Slider(quality.toFloat(), { quality = it.toInt() }, valueRange = 60f..100f, steps = 39)
            }
            SwitchRow("Keep creator & copyright", "Author credit and copyright notice are written into the export", keepCredit) { keepCredit = it }
            Note("GPS, device and timestamp EXIF are not copied into exports. If the image declares AI generation, that disclosure is always written into the exported file.",
                Icons.Default.Lock)
            if (a.container.hasC2pa) Note("C2PA credentials cannot survive resizing; their AI disclosure (if any) is carried as IPTC metadata.", Icons.Default.WarningAmber, StatusColors.warn)
            TextButton(onClick = { showSave = true }) { Text("Save as custom preset…") }
        }
        Button(onClick = { vm.export(opts) }, Modifier.fillMaxWidth().height(52.dp), enabled = output !is Ui.Loading) {
            Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(8.dp)); Text("Export for ${preset.name}")
        }
        OutputCard(output, onViewReport = { act.navigate(Dest.REPORTS) }, onDismiss = vm::clearOutput)
    }

    if (showSave) CustomPresetDialog(effective, onDismiss = { showSave = false }) { p -> vm.savePreset(p); showSave = false }
}

@Composable
private fun CustomPresetDialog(base: ExportPreset, onDismiss: () -> Unit, onSave: (ExportPreset) -> Unit) {
    var name by remember { mutableStateOf("My preset") }
    var w by remember { mutableStateOf(if (base.aspectW == 0) "" else base.aspectW.toString()) }
    var h by remember { mutableStateOf(if (base.aspectH == 0) "" else base.aspectH.toString()) }
    var edge by remember { mutableStateOf(base.maxLongEdge.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom export preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("Ratio W") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("Ratio H") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(edge, { edge = it.filter(Char::isDigit).take(5) }, label = { Text("Max long edge (px)") }, singleLine = true)
                Text("Leave ratio empty to keep the original aspect ratio.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = w.toIntOrNull() ?: 0; val hh = h.toIntOrNull() ?: 0
                onSave(base.copy(name = name.ifBlank { "Custom" }, aspectW = if (ww > 0 && hh > 0) ww else 0, aspectH = if (ww > 0 && hh > 0) hh else 0,
                    maxLongEdge = (edge.toIntOrNull() ?: 2048).coerceIn(64, 8192), custom = true))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
