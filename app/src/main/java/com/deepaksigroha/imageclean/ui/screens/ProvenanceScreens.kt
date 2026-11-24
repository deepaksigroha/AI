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
import com.deepaksigroha.imageclean.core.ai.AiProvenanceScanner
import com.deepaksigroha.imageclean.core.ai.AiVerdict
import com.deepaksigroha.imageclean.core.ai.EvidenceKind
import com.deepaksigroha.imageclean.core.ai.EvidenceStrength
import com.deepaksigroha.imageclean.core.c2pa.C2paState
import com.deepaksigroha.imageclean.core.c2pa.CheckResult
import com.deepaksigroha.imageclean.ui.AppActions
import com.deepaksigroha.imageclean.ui.Dest
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.components.*
import com.deepaksigroha.imageclean.ui.theme.StatusColors
import com.deepaksigroha.imageclean.ui.value

fun c2paColor(s: C2paState) = when (s) {
    C2paState.VERIFIED -> StatusColors.good; C2paState.INVALID -> StatusColors.bad
    C2paState.UNVERIFIABLE -> StatusColors.warn; C2paState.NONE -> StatusColors.neutral
}

@Composable
fun C2paScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)
    val r = a.c2pa

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)
        StatusBanner(r.state.title, r.summary.ifBlank { "This file does not carry Content Credentials. That is normal for most photos and says nothing about whether it is authentic." },
            c2paColor(r.state), when (r.state) {
                C2paState.VERIFIED -> Icons.Default.VerifiedUser; C2paState.INVALID -> Icons.Default.GppBad
                C2paState.UNVERIFIABLE -> Icons.Default.GppMaybe; C2paState.NONE -> Icons.Default.Shield
            })

        if (r.checks.isNotEmpty()) SectionCard("Validation checks", Icons.Default.Checklist) {
            r.checks.forEach { ch ->
                val (color, label) = when (ch.result) { CheckResult.PASS -> StatusColors.good to "PASS"; CheckResult.FAIL -> StatusColors.bad to "FAIL"; CheckResult.NOT_CHECKED -> StatusColors.warn to "NOT VERIFIED" }
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    StatusChip(label, color); Spacer(Modifier.width(8.dp))
                    Column { Text(ch.name, style = MaterialTheme.typography.labelLarge); Text(ch.detail, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        r.manifests.forEach { m ->
            SectionCard(if (m.isActive) "Active manifest" else "Earlier manifest (ingredient history)", Icons.Default.Receipt,
                subtitle = m.label, collapsible = true, initiallyExpanded = m.isActive) {
                m.claimGenerator?.let { InfoRow("Claim generator", it) }
                m.title?.let { InfoRow("Title", it) }
                m.format?.let { InfoRow("Format", it) }
                m.author?.let { InfoRow("Author", it) }
                m.signerSubject?.let { InfoRow("Signed by", it) }
                m.signerIssuer?.let { InfoRow("Certificate issuer", it) }
                m.signingAlg?.let { InfoRow("Algorithm", it) }
                if (m.actions.isNotEmpty()) {
                    Text("Actions (edit history)", style = MaterialTheme.typography.labelLarge)
                    m.actions.forEach { ac ->
                        val ai = ac.digitalSourceType?.contains("trainedAlgorithmic", true) == true
                        Bullet(listOfNotNull(ac.action, ac.softwareAgent?.let { "by $it" }, ac.description, ac.digitalSourceType?.substringAfterLast('/')?.let { "source: $it" }, ac.whenTime)
                            .joinToString(" · "), if (ai) StatusColors.warn else MaterialTheme.colorScheme.primary)
                    }
                }
                if (m.ingredients.isNotEmpty()) { Text("Ingredients", style = MaterialTheme.typography.labelLarge); m.ingredients.forEach { Bullet(it) } }
                m.trainingMining?.let { InfoRow("Training & data-mining", it.take(300)) }
                Text("Assertions: " + m.assertionLabels.joinToString(), style = MaterialTheme.typography.bodySmall)
                var raw by remember { mutableStateOf(false) }
                TextButton(onClick = { raw = !raw }) { Text(if (raw) "Hide claim" else "Show raw claim") }
                if (raw) Text(m.claimJson, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }

        SectionCard("About verification", Icons.Default.Info, collapsible = true, initiallyExpanded = r.state == C2paState.UNVERIFIABLE) {
            Text("ImageClean Pro checks the COSE signature over the claim, the certificate chain, the hash of every assertion and the hash binding to the image bytes – entirely offline.",
                style = MaterialTheme.typography.bodySmall)
            Text("Signer trust is evaluated against your device's certificate store plus any C2PA trust-list roots you import in Settings. Timestamps, revocation (OCSP) and BMFF (HEIF/video) hash bindings are not checked offline, so such files are reported as unverifiable rather than verified.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { act.navigate(Dest.SETTINGS) }) { Text("Manage trust anchors") }
        }
    }
}

@Composable
fun AiScreen(vm: MainViewModel, act: AppActions) {
    val state by vm.analysis.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val a = state.value ?: return NeedImage(state, act.pickImage, act.pickFile)
    val ai = a.ai
    val color = when (ai.verdict) {
        AiVerdict.AI_GENERATED -> StatusColors.bad; AiVerdict.AI_ASSISTED -> StatusColors.warn
        AiVerdict.C2PA_VERIFIED_NO_AI -> StatusColors.good; AiVerdict.C2PA_INVALID -> StatusColors.bad
        AiVerdict.NONE -> StatusColors.info; AiVerdict.UNDETERMINED -> StatusColors.neutral
    }

    ScreenColumn {
        ImageHeader(a, preview, act.pickImage)
        StatusBanner(ai.verdict.title, ai.explanation, color, Icons.Default.AutoAwesome)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("C2PA: " + when (ai.c2paState) { C2paState.VERIFIED -> "verified"; C2paState.INVALID -> "invalid"; C2paState.UNVERIFIABLE -> "unverifiable"; C2paState.NONE -> "none" }, c2paColor(ai.c2paState))
            StatusChip(if (ai.cryptographic) "Cryptographic evidence" else if (ai.evidence.isNotEmpty()) "Metadata evidence" else "No evidence", if (ai.cryptographic) StatusColors.good else StatusColors.neutral)
        }

        EvidenceKind.entries.forEach { kind ->
            val items = ai.evidence.filter { it.kind == kind }
            if (items.isNotEmpty()) SectionCard(kind.label, when (kind) {
                EvidenceKind.CRYPTOGRAPHIC -> Icons.Default.VerifiedUser; EvidenceKind.SIGNED_UNVERIFIED -> Icons.Default.GppMaybe; EvidenceKind.METADATA -> Icons.Default.Label
            }) {
                items.forEach { e ->
                    Row {
                        StatusChip(when (e.strength) { EvidenceStrength.STRONG -> "Strong"; EvidenceStrength.MODERATE -> "Moderate"; EvidenceStrength.WEAK -> "Weak" },
                            when (e.strength) { EvidenceStrength.STRONG -> StatusColors.bad; EvidenceStrength.MODERATE -> StatusColors.warn; EvidenceStrength.WEAK -> StatusColors.neutral })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(e.finding, style = MaterialTheme.typography.bodyMedium)
                            Text(e.source + if (e.generated) " · generation" else " · editing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (ai.evidence.isEmpty()) SectionCard("What was checked", Icons.Default.Checklist) {
            listOf("C2PA Content Credentials", "IPTC Digital Source Type", "XMP AI-generation properties and creator tool", "EXIF Software and text fields",
                "Known AI generator signatures (DALL·E, Midjourney, Stable Diffusion, Firefly, Imagen/Gemini, …)", "PNG generation parameters", "Digital signatures and certificates")
                .forEach { Bullet(it) }
        }
        Note(AiProvenanceScanner.DISCLAIMER, Icons.Default.Info)
        OutlinedButton(onClick = { act.navigate(Dest.C2PA) }, Modifier.fillMaxWidth()) { Text("Open C2PA Inspector") }
    }
}
