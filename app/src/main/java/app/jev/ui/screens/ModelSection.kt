package app.jev.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import app.jev.core.DiagnosticReport
import app.jev.ml.ModelDownloadWorker
import app.jev.ml.ModelState
import app.jev.ml.SelfTestState
import app.jev.ui.MainViewModel

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

/** Download → install → self-test, all from one card. */
@Composable
fun ModelCard(vm: MainViewModel, onImport: () -> Unit) {
    val model by vm.modelState.collectAsState()
    val work by vm.modelDownload.collectAsState()
    val selfTest by vm.selfTest.collectAsState()
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf<DiagnosticReport?>(null) }
    val active = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On-device model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                active -> DownloadProgress(work!!)
                model is ModelState.Ready -> {
                    val m = model as ModelState.Ready
                    StatusLine(true, "Installed · ${m.sizeMb} MB · ~${m.msPerDecision} ms per decision")
                    Text(m.details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                model is ModelState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading model…")
                }
                model is ModelState.Failed -> StatusLine(false, "Failed to load: ${(model as ModelState.Failed).message}. Using built-in rules.")
                else -> Text(
                    "Not installed. Jev is using its built-in rules. Download the ModernBERT decision model (a one-time download of a few hundred MB) for smarter decisions. Your notifications are never uploaded; this is the only thing Jev downloads.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!active && work?.state == WorkInfo.State.FAILED) {
                StatusLine(false, work?.outputData?.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed")
            }

            if (!active && model !is ModelState.Ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi only", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    active -> OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel") }
                    model is ModelState.Ready -> {
                        Button(onClick = vm::runSelfTest, enabled = selfTest !is SelfTestState.Running) { Text("Run self-test") }
                        OutlinedButton(onClick = vm::deleteModel) { Text("Remove") }
                    }
                    else -> {
                        Button(onClick = { vm.downloadModel(wifiOnly) }) { Text(if (work?.state == WorkInfo.State.FAILED) "Retry download" else "Download model") }
                        TextButton(onClick = onImport) { Text("Import files") }
                    }
                }
            }

            when (val t = selfTest) {
                is SelfTestState.Running -> {
                    Text("Self-test ${t.done}/${t.total}…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (t.total > 0) t.done.toFloat() / t.total else 0f }, modifier = Modifier.fillMaxWidth())
                }
                is SelfTestState.Done -> SelfTestSummary(t.report) { showDetails = t.report }
                is SelfTestState.Error -> StatusLine(false, "Self-test error: ${t.message}")
                SelfTestState.Idle -> Unit
            }
        }
    }
    showDetails?.let { SelfTestDialog(it) { showDetails = null } }
}

@Composable
private fun DownloadProgress(work: WorkInfo) {
    val stage = work.progress.getString(ModelDownloadWorker.KEY_STAGE)
    val done = work.progress.getLong(ModelDownloadWorker.KEY_DONE, 0)
    val total = work.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0)
    val text = when {
        work.state == WorkInfo.State.ENQUEUED && work.runAttemptCount > 0 -> "Connection lost, will resume automatically…"
        work.state == WorkInfo.State.ENQUEUED -> "Waiting for a network connection…"
        stage == ModelDownloadWorker.STAGE_INSTALLING -> "Installing…"
        stage == ModelDownloadWorker.STAGE_TESTING -> "Testing the model on this phone…"
        total > 0 -> "Downloading ${done / (1024 * 1024)} of ${total / (1024 * 1024)} MB"
        else -> "Starting download…"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    if (stage == ModelDownloadWorker.STAGE_DOWNLOADING && total > 0) {
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text("You can leave the app; the download continues in the background.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SelfTestSummary(r: DiagnosticReport, onDetails: () -> Unit) {
    val healthy = r.errors == 0 && r.wellFormed
    StatusLine(
        healthy && r.accuracy >= 0.6f,
        "Self-test: ${r.passed}/${r.total} correct (built-in rules: ${(r.rulesAccuracy * r.total).toInt()}/${r.total}) · ${r.medianMillis} ms per decision",
    )
    r.byFeature().forEach { (feature, s) ->
        Text("$feature: ${s.first}/${s.second}", style = MaterialTheme.typography.labelMedium)
    }
    if (!healthy) Text("Some decisions failed or were malformed, so Jev will fall back to rules for those.", style = MaterialTheme.typography.labelSmall, color = Red)
    TextButton(onClick = onDetails) { Text("See every test") }
}

@Composable
private fun SelfTestDialog(r: DiagnosticReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Self-test: ${r.passed}/${r.total}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(r.results) { res ->
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (res.passed) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = if (res.passed) "Pass" else "Fail",
                            tint = if (res.passed) Green else Red,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text("${res.case.feature} · ${res.case.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            val d = res.decision
                            Text(
                                if (d == null) "Error: ${res.error}" else "→ ${d.choice} (${(d.confidence * 100).toInt()}%, ${res.millis} ms)",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (!res.passed) Text("expected: ${res.case.accepted.joinToString(" / ")}", style = MaterialTheme.typography.labelSmall, color = Red)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One-tap end-to-end check: permissions, listener, scam shield, triage, money, model. */
@Composable
fun PipelineTestCard(vm: MainViewModel) {
    val test by vm.pipelineTest.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check that everything works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Jev sends itself three test notifications (a parcel scam, a promo and an emergency) and checks that each is caught and sorted correctly.",
                style = MaterialTheme.typography.bodySmall,
            )
            test?.checks?.forEach { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        when { c.ok -> Icons.Filled.CheckCircle; c.required -> Icons.Filled.Error; else -> Icons.Filled.Info },
                        contentDescription = null,
                        tint = when { c.ok -> Green; c.required -> Red; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(c.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(c.detail, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val t = test
            if (t != null && !t.running) {
                StatusLine(t.allRequiredPassed, if (t.allRequiredPassed) "Everything is working" else "Something needs attention (see above)")
            }
            Button(onClick = vm::runPipelineTest, enabled = test?.running != true) {
                if (test?.running == true) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (test == null) "Run check" else "Run again")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (ok) Green else Red, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
