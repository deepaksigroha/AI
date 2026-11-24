package com.deepaksigroha.imageclean.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepaksigroha.imageclean.data.ImageAnalysis
import com.deepaksigroha.imageclean.data.MediaSaver
import com.deepaksigroha.imageclean.data.ReportGenerator
import com.deepaksigroha.imageclean.ui.SavedOutput
import com.deepaksigroha.imageclean.ui.Ui
import com.deepaksigroha.imageclean.ui.theme.StatusColors

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (collapsible) Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusBanner(title: String, body: String, color: Color, icon: ImageVector) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun Note(text: String, icon: ImageVector = Icons.Default.Info, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Header showing the currently selected image. */
@Composable
fun ImageHeader(a: ImageAnalysis, preview: ImageBitmap?, onChange: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (preview != null) Image(preview, "Selected image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.format.label} · ${ReportGenerator.fmtSize(a.size)} · ${a.width}×${a.height}", style = MaterialTheme.typography.bodySmall)
                Text(a.metadataStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            }
            TextButton(onClick = onChange) { Text("Change") }
        }
    }
}

/** Shown on tool screens when no image is selected yet. */
@Composable
fun NeedImage(state: Ui<ImageAnalysis>, onPick: () -> Unit, onPickFile: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            Ui.Loading -> { CircularProgressIndicator(); Text("Analysing image locally…") }
            is Ui.Error -> { Icon(Icons.Default.ErrorOutline, null, tint = StatusColors.bad, modifier = Modifier.size(48.dp)); Text(state.message) }
            else -> {
                Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Select an image to begin", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state !is Ui.Loading) {
            Button(onClick = onPick) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Choose from Photos") }
            OutlinedButton(onClick = onPickFile) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open file (keeps GPS data)") }
            Note("The Android Photo Picker may hide GPS location. Use “Open file” to inspect the complete original metadata.")
        }
    }
}

/** Result card for clean/export/edit operations. */
@Composable
fun OutputCard(state: Ui<SavedOutput>, onViewReport: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    when (state) {
        Ui.Idle -> {}
        Ui.Loading -> ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Processing locally…")
            }
        }
        is Ui.Error -> StatusBanner("Failed", state.message, StatusColors.bad, Icons.Default.ErrorOutline)
        is Ui.Ready -> {
            val o = state.value
            SectionCard(o.title, Icons.Default.CheckCircle, subtitle = o.path) {
                if (o.removed.isNotEmpty()) { Text("Removed", style = MaterialTheme.typography.labelLarge); o.removed.forEach { Bullet(it, StatusColors.bad) } }
                if (o.retained.isNotEmpty()) { Text("Retained", style = MaterialTheme.typography.labelLarge); o.retained.forEach { Bullet(it, StatusColors.good) } }
                o.warnings.forEach { Note(it, Icons.Default.WarningAmber, StatusColors.warn) }
                Note("Your original file was not modified.", Icons.Default.Lock)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ctx.startActivity(MediaSaver.shareIntent(o.uri, o.mime)) }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                    OutlinedButton(onClick = onViewReport) { Text("Report") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
