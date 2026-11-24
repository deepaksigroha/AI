package com.deepaksigroha.imageclean.ui.screens

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.data.WatermarkRegion
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.Dest
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.Ui
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.theme.StatusColors
import com.deepaksigroha.imageclean.ui.value

@Composable
fun WatermarkScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val wm by vm.watermark.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)
    var selected by remember(a) { mutableStateOf<Int?>(null) }
    var owner by remember(a) { mutableStateOf(false) }

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)
        val regions = wm.value?.regions.orEmpty()
        val img = preview
        if (img != null) {
            val ratio = img.width.toFloat() / img.height
            ElevatedCard(Modifier.fillMaxWidth()) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                    drawImage(img, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
                    regions.forEachIndexed { i, r ->
                        val c = if (r.aiLogo) StatusColors.bad else StatusColors.warn
                        drawRect(c, Offset(r.rect.left * size.width, r.rect.top * size.height),
                            Size(r.rect.width() * size.width, r.rect.height() * size.height), style = Stroke(width = if (selected == i) 6f else 3f))
                    }
                }
            }
        }
        when (val w = wm) {
            Ui.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is Ui.Error -> Text(w.message)
            is Ui.Ready -> {
                if (w.value.regions.isEmpty()) StatusBanner("No visible watermark regions detected", "No corner logos, AI marks or stamped text were found by the heuristic scan.",
                    StatusColors.good, Icons.Default.CheckCircle)
                else SectionCard("Detected regions", Icons.Default.BrandingWatermark, subtitle = "Tap a region to magnify it") {
                    w.value.regions.forEachIndexed { i, r ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (selected == i) null else i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusChip("${(r.confidence * 100).toInt()}%", if (r.aiLogo) StatusColors.bad else StatusColors.warn)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.label, style = MaterialTheme.typography.bodyMedium)
                                Text(position(r.rect), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ZoomIn, "Magnify")
                        }
                    }
                }
                w.value.notes.forEach { Note(it) }
            }
            Ui.Idle -> OutlinedButton(onClick = vm::runWatermark) { Text("Scan for watermarks") }
        }

        val sel = selected?.let { regions.getOrNull(it) }
        if (sel != null && img != null) SectionCard("Magnified inspection", Icons.Default.ZoomIn, subtitle = sel.label) {
            val pad = 0.04f
            val l = ((sel.rect.left - pad).coerceAtLeast(0f) * img.width).toInt()
            val t = ((sel.rect.top - pad).coerceAtLeast(0f) * img.height).toInt()
            val rr = ((sel.rect.right + pad).coerceAtMost(1f) * img.width).toInt().coerceAtLeast(l + 1)
            val bb = ((sel.rect.bottom + pad).coerceAtMost(1f) * img.height).toInt().coerceAtLeast(t + 1)
            val ratio = (rr - l).toFloat() / (bb - t)
            Canvas(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.3f, 6f))) {
                drawImage(img, srcOffset = IntOffset(l, t), srcSize = IntSize(rr - l, bb - t),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
            }

            HorizontalDivider()
            Text("Editing tools (your own images only)", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { owner = !owner }) {
                Checkbox(owner, { owner = it })
                Text("I own this image or am authorised to edit it", style = MaterialTheme.typography.bodyMedium)
            }
            val keep = cropAway(sel.rect)
            if (keep == null) Note("This region is not at an edge, so it cannot be cropped away without losing most of the image.")
            else Button(onClick = { vm.cropCopy(keep) }, enabled = owner && output !is Ui.Loading) {
                Icon(Icons.Default.Crop, null); Spacer(Modifier.width(6.dp))
                Text("Save a cropped copy without this ${edgeName(sel.rect)} band")
            }
            Note("Copyright notices and attribution are never removed automatically. If the image is AI-generated, the edited copy still carries an AI disclosure in its metadata.",
                Icons.Default.Gavel)
        }
        OutputCard(output, onViewReport = { act.navigate(Dest.REPORTS) }, onDismiss = vm::clearOutput)
    }
}

private fun position(r: RectF): String {
    val v = when { r.centerY() < 0.33f -> "top"; r.centerY() > 0.66f -> "bottom"; else -> "middle" }
    val h = when { r.centerX() < 0.33f -> "left"; r.centerX() > 0.66f -> "right"; else -> "centre" }
    return "$v-$h · ${(r.width() * 100).toInt()}% × ${(r.height() * 100).toInt()}% of image"
}

/** Returns the largest rectangle that excludes [r] by cropping one edge band, or null if that would lose >35%. */
private fun cropAway(r: RectF): RectF? {
    val options = listOf(
        RectF(0f, 0f, 1f, r.top) to (1f - r.top),        // remove bottom band
        RectF(0f, r.bottom, 1f, 1f) to r.bottom,         // remove top band
        RectF(0f, 0f, r.left, 1f) to (1f - r.left),      // remove right band
        RectF(r.right, 0f, 1f, 1f) to r.right,           // remove left band
    )
    val best = options.minByOrNull { it.second } ?: return null
    return if (best.second <= 0.35f) best.first else null
}

private fun edgeName(r: RectF): String {
    val losses = listOf("bottom" to 1f - r.top, "top" to r.bottom, "right" to 1f - r.left, "left" to r.right)
    return losses.minBy { it.second }.first
}
