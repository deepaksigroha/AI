package com.deepaksigroha.imageclean.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.R
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.Dest
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.Ui
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.value

@Composable
fun HomeScreen(vm: MainViewModel, a: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()

    ScreenColumn {
        // Brand header
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF0EA5E9)))).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_launcher_foreground), "ImageClean Pro logo", Modifier.size(84.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("ImageClean Pro", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text("AI & Metadata Inspector", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
                    Spacer(Modifier.height(4.dp))
                    Text("Developed by Deepak Sigroha", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }

        val analysis = state.value
        if (analysis != null) ImageHeader(analysis, preview, a.pickImage)
        else if (state is Ui.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (state is Ui.Error) StatusBanner("Could not open image", (state as Ui.Error).message, com.deepaksigroha.imageclean.ui.theme.StatusColors.bad, Icons.Default.ErrorOutline)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Select Image", Icons.Default.AddPhotoAlternate, Modifier.weight(1f), primary = true) { a.pickImage() }
            ActionTile("Open File", Icons.Default.FolderOpen, Modifier.weight(1f)) { a.pickFile() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Inspect Metadata", Icons.Default.ManageSearch, Modifier.weight(1f)) { a.navigate(Dest.INSPECTOR) }
            ActionTile("Clean Privacy Metadata", Icons.Default.CleaningServices, Modifier.weight(1f)) { a.navigate(Dest.CLEANER) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Check AI Provenance", Icons.Default.AutoAwesome, Modifier.weight(1f)) { a.navigate(Dest.AI) }
            ActionTile("Instagram Compatibility", Icons.Default.PhotoCamera, Modifier.weight(1f)) { a.navigate(Dest.INSTAGRAM) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Social Media Export", Icons.Default.IosShare, Modifier.weight(1f)) { a.navigate(Dest.EXPORT) }
            ActionTile("Batch Processing", Icons.Default.Collections, Modifier.weight(1f)) { a.navigate(Dest.BATCH) }
        }

        // Module K – transparency dashboard
        SectionCard("Transparency dashboard", Icons.Default.Insights, subtitle = "Calculated locally from your history on this device") {
            val s = stats
            if (s == null || s.total == 0 && s.processed == 0) Text("No images inspected yet.", style = MaterialTheme.typography.bodyMedium)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Inspected", s.total, Modifier.weight(1f)); Stat("With EXIF", s.withExif, Modifier.weight(1f)); Stat("With GPS", s.withGps, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("With C2PA", s.withC2pa, Modifier.weight(1f)); Stat("AI provenance", s.withAi, Modifier.weight(1f)); Stat("Processed", s.processed, Modifier.weight(1f))
                }
                Row { Stat("Verification failures", s.failures, Modifier.fillMaxWidth()) }
            }
        }
        Note("All processing happens on your device. ImageClean Pro has no internet permission, no ads and no tracking.", Icons.Default.Shield)
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val colors = if (primary) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.elevatedCardColors()
    ElevatedCard(onClick = onClick, modifier = modifier.heightIn(min = 96.dp), colors = colors, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Stat(label: String, n: Int, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(10.dp)) {
            Text(n.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
