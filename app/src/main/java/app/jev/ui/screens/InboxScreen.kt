package app.jev.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.WorkInfo
import app.jev.core.scam.ScamVerdict
import app.jev.ml.ModelState
import app.jev.core.triage.TriageBucket
import app.jev.data.NotificationRecord
import app.jev.ui.MainViewModel
import app.jev.ui.theme.bucketColor
import app.jev.ui.theme.verdictColor

@Composable
fun InboxScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val all by vm.notifications.collectAsState()
    val settings by vm.settings.collectAsState()
    val model by vm.modelState.collectAsState()
    val download by vm.modelDownload.collectAsState()
    var hasAccess by remember { mutableStateOf(vm.hasNotificationAccess()) }
    LifecycleResumeEffect(Unit) {
        hasAccess = vm.hasNotificationAccess()
        onPauseOrDispose { }
    }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NotificationRecord?>(null) }
    val shown = if (filter == null) all else all.filter { it.bucket == filter }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                "Notification triage",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
        }
        if (!hasAccess) {
            item {
                InfoCard(
                    "Give Jev notification access",
                    "Jev reads notifications on this phone to sort them, catch scams and track spending. They are processed only on the device and never uploaded.",
                ) {
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open settings") }
                }
            }
        } else if (!settings.actOnNotifications) {
            item {
                InfoCard(
                    "Shadow mode",
                    "Jev is labelling notifications but not hiding any. Tap an item to correct it. Turn on \"Hide digest & silenced\" in Settings once you trust it.",
                )
            }
        }
        if (model is ModelState.NotInstalled && download?.state != WorkInfo.State.RUNNING && download?.state != WorkInfo.State.ENQUEUED) {
            item {
                InfoCard(
                    "Get the on-device model",
                    "Jev works now with built-in rules. Download the ModernBERT decision model (about 150 MB, on Wi-Fi) for smarter decisions. It runs fully offline afterwards and Jev tests it automatically.",
                ) {
                    Button(onClick = { vm.downloadModel(wifiOnly = true) }) { Text("Download model") }
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${all.size}") })
                TriageBucket.entries.forEach { b ->
                    val n = all.count { it.bucket == b.name }
                    FilterChip(selected = filter == b.name, onClick = { filter = b.name }, label = { Text("${b.label} $n") })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (all.isEmpty()) "No notifications yet. They'll appear here as they arrive." else "Nothing in this bucket.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(shown, key = { it.id }) { r ->
            NotificationRow(r) { selected = r }
            HorizontalDivider()
        }
    }

    selected?.let { r -> DecisionDialog(r, onDismiss = { selected = null }, onTeach = { vm.teach(r, it); selected = null }) }
}

@Composable
private fun NotificationRow(r: NotificationRecord, onClick: () -> Unit) {
    val bucket = TriageBucket.valueOf(r.bucket)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.appLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ${shortTime(r.postedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            r.scamVerdict?.let { v ->
                val verdict = ScamVerdict.valueOf(v)
                if (verdict != ScamVerdict.SAFE) Pill(verdict.label, verdictColor(verdict))
            }
            Pill(bucket.label, bucketColor(bucket))
        }
        Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DecisionDialog(r: NotificationRecord, onDismiss: () -> Unit, onTeach: (TriageBucket) -> Unit) {
    val probs = r.probabilities.split(';').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { TriageBucket.valueOf(k) to v.toFloat() }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.sender ?: r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.text, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Decision: ${r.reason}" + if (r.latencyMs > 0) " · ${r.latencyMs} ms" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                probs.forEach { (b, p) -> ProbabilityBar(b.label, p, bucketColor(b)) }
                Spacer(Modifier.height(8.dp))
                Text("Always treat ${r.sender ?: r.title} from ${r.appLabel} as:", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TriageBucket.entries.forEach { b ->
                        FilterChip(selected = b.name == r.bucket, onClick = { onTeach(b) }, label = { Text(b.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
