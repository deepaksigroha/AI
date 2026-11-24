package com.deepaksigroha.imageclean.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.BuildConfig
import com.deepaksigroha.imageclean.R
import com.deepaksigroha.imageclean.data.ThemeMode
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.components.*

@Composable
fun SettingsScreen(vm: MainViewModel, act: AppActions) {
    val theme by vm.prefs.theme.collectAsStateWithLifecycle()
    val dynamic by vm.prefs.dynamicColor.collectAsStateWithLifecycle()
    val quality by vm.prefs.defaultQuality.collectAsStateWithLifecycle()
    val keepCredit by vm.prefs.keepCredit.collectAsStateWithLifecycle()
    var anchors by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importTrust(it) } }
    LaunchedEffect(Unit) { while (true) { anchors = vm.trust.userAnchors().size; kotlinx.coroutines.delay(1500) } }

    ScreenColumn {
        SectionCard("Appearance", Icons.Default.Palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(selected = theme == m, onClick = { vm.prefs.setTheme(m) },
                        label = { Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }) })
                }
            }
            SwitchRow("Material You colours", "Use wallpaper-based colours (Android 12+)", dynamic) { vm.prefs.setDynamic(it) }
        }
        SectionCard("Export defaults", Icons.Default.IosShare) {
            Text("Default JPEG quality: $quality", style = MaterialTheme.typography.labelLarge)
            Slider(quality.toFloat(), { vm.prefs.setQuality(it.toInt()) }, valueRange = 60f..100f, steps = 39)
            SwitchRow("Keep creator & copyright in exports", null, keepCredit) { vm.prefs.setKeepCredit(it) }
        }
        SectionCard("C2PA trust anchors", Icons.Default.VerifiedUser, subtitle = "$anchors imported certificate(s) + device CA store") {
            Text("To mark Content Credentials as fully verified, the signer must chain to a trusted root. Import root certificates (PEM/DER) from the official C2PA trust list or from issuers you trust. Nothing is downloaded automatically.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { certPicker.launch(arrayOf("application/x-x509-ca-cert", "application/x-pem-file", "application/pkix-cert", "application/octet-stream", "text/plain", "*/*")) }) {
                    Text("Import certificates")
                }
                OutlinedButton(onClick = { vm.clearTrust() }, enabled = anchors > 0) { Text("Remove all") }
            }
        }
        SectionCard("Data & privacy", Icons.Default.Shield) {
            Bullet("No internet permission – the app cannot send data anywhere.")
            Bullet("History and reports are stored only in this app's private storage and are excluded from cloud backup.")
            OutlinedButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text("Clear history") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("This permanently deletes all stored inspection records and reports from this device. Exported images are not affected.") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
fun AboutScreen() {
    ScreenColumn {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
            Text("ImageClean Pro", style = MaterialTheme.typography.headlineMedium)
            Text("AI & Metadata Inspector", style = MaterialTheme.typography.titleMedium)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Developer", Icons.Default.Person) {
            InfoRow("Developed by", "Deepak Sigroha")
            InfoRow("Licence", "GNU GPL-3.0 (open source)")
        }
        SectionCard("What this app does", Icons.Default.Info) {
            Bullet("Inspects EXIF, IPTC, XMP, ICC and C2PA metadata in JPEG, PNG, WebP and HEIC/HEIF images.")
            Bullet("Removes privacy-sensitive metadata into a new copy – never touching the original.")
            Bullet("Validates C2PA Content Credentials offline and explains AI provenance evidence.")
            Bullet("Explains why platforms such as Instagram may show an AI label – without promising to remove it.")
        }
        SectionCard("Privacy policy", Icons.Default.Policy, collapsible = true, initiallyExpanded = false) {
            Text(PRIVACY_POLICY, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Open-source licence", Icons.Default.Gavel, collapsible = true, initiallyExpanded = false) {
            Text("ImageClean Pro is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3. It is distributed WITHOUT ANY WARRANTY. See the LICENSE file in the source repository for the full text.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

const val PRIVACY_POLICY = """ImageClean Pro processes images only on your device.
• The app requests no internet permission and contains no advertising, analytics or tracking SDKs.
• Images you open are read only to display and analyse them. Copies you export are saved to your Pictures/ImageClean Pro folder.
• Inspection history and reports are stored in the app's private storage, excluded from cloud backup, and can be deleted at any time in Settings.
• Location (GPS) data is shown to you only so you can decide whether to remove it; it is never collected or transmitted.
• Certificates you import for C2PA verification stay on your device.
Contact: Deepak Sigroha (developer)."""
