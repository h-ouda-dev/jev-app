package app.heron.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import app.heron.BuildConfig
import app.heron.core.DiagnosticReport
import app.heron.ml.ModelDownloadWorker
import app.heron.ml.ModelState
import app.heron.ml.SelfTestState
import app.heron.ui.MainViewModel
import app.heron.ui.components.HeronCard
import app.heron.ui.components.IconBadge
import app.heron.ui.components.Meter
import app.heron.ui.components.minutesLabel
import app.heron.ui.theme.Heron

private val DIGEST_OPTIONS = listOf(8 * 60, 10 * 60 + 30, 12 * 60 + 30, 15 * 60, 18 * 60 + 30, 21 * 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    val rules by vm.senderRules.collectAsState()
    var vipText by remember(s.vipNames) { mutableStateOf(s.vipNames.joinToString(", ")) }
    var budgetText by remember(s.monthlyBudget) { mutableStateOf(if (s.monthlyBudget > 0) "%.0f".format(s.monthlyBudget) else "") }
    var currencyText by remember(s.currency) { mutableStateOf(s.currency) }
    var showLicences by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importModel(uris)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 32.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 8.dp))
            }

            item { Group("Heron AI") { ModelCard(vm, onImport = { picker.launch(arrayOf("*/*")) }) } }

            item {
                Group("Sorting") {
                    Toggle(
                        "Hide digest & muted notifications",
                        "Off is preview mode: Heron only labels. Calls, alarms and likely scams are never hidden.",
                        s.actOnNotifications,
                    ) { v -> vm.updateSettings { it.copy(actOnNotifications = v) } }
                    Divider()
                    Label("Important people")
                    Text("Always urgent, never muted. Use the name shown in notifications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = vipText,
                        onValueChange = { vipText = it },
                        placeholder = { Text("Mom, Ahmed, Sara") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(onClick = { vm.updateSettings { it.copy(vipNames = vipText.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) } }) { Text("Save") }
                        },
                    )
                    Divider()
                    Label("Digest times")
                    Text("Held-back notifications arrive together at these times.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DIGEST_OPTIONS.forEach { m ->
                            val on = m in s.digestTimes
                            Surface(
                                onClick = { vm.updateSettings { it.copy(digestTimes = if (on) it.digestTimes - m else it.digestTimes + m) } },
                                shape = CircleShape,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text(
                                    minutesLabel(m),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                    if (rules.isNotEmpty()) {
                        Divider()
                        Label("What you've taught Heron")
                        rules.forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.heron.core.triage.TriageBucket.valueOf(r.bucket).label,
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                )
                                IconButton(onClick = { vm.forgetRule(r) }) { Icon(Icons.Rounded.Close, "Forget", modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }

            item {
                Group("Shield") {
                    Toggle("Warn me about scams", "An alert with the red-flag checklist when a message looks dangerous.", s.scamAlerts) { v ->
                        vm.updateSettings { it.copy(scamAlerts = v) }
                    }
                }
            }

            item {
                Group("Money") {
                    Toggle("Flag odd charges", "Duplicates, unusually large payments and subscription price rises.", s.spendAlerts) { v ->
                        vm.updateSettings { it.copy(spendAlerts = v) }
                    }
                    Divider()
                    Label("Monthly budget")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = budgetText,
                            onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' } },
                            placeholder = { Text("6000") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = currencyText,
                            onValueChange = { currencyText = it.uppercase().take(3) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.width(92.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = { vm.updateSettings { it.copy(monthlyBudget = budgetText.toDoubleOrNull() ?: 0.0, currency = currencyText.ifBlank { "QAR" }) } },
                        shape = CircleShape,
                    ) { Text("Save budget") }
                }
            }

            item { Group("Diagnostics") { PipelineTest(vm) } }

            item {
                Group("Privacy") {
                    Row(verticalAlignment = Alignment.Top) {
                        IconBadge(Icons.Rounded.Lock, MaterialTheme.colorScheme.primary, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Heron thinks on your phone. Notifications, messages and transactions are never uploaded. The only download is Heron's AI model. History older than ${s.keepDays} days is deleted automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    NavRow(Icons.Rounded.Notifications, "Notification access") { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    NavRow(Icons.Rounded.Notifications, "Heron's alert settings") {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = vm::clearHistory, shape = CircleShape, border = BorderStroke(1.dp, Heron.colors.danger.copy(alpha = 0.5f))) {
                        Text("Delete all history", color = Heron.colors.danger)
                    }
                }
            }

            item {
                Group("About") {
                    NavRow(Icons.Rounded.Info, "Version ${BuildConfig.VERSION_NAME}", chevron = false) {}
                    NavRow(Icons.Rounded.Info, "Open-source licences") { showLicences = true }
                    if (BuildConfig.DEBUG) NavRow(Icons.Rounded.Science, "Load demo data (debug)") { vm.loadDemoData() }
                }
            }
        }
    }

    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } },
            title = { Text("Open-source licences") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    listOf(
                        "ModernBERT-base (Answer.AI) · Apache-2.0",
                        "ModernBERT-base-nli (tasksource) · Apache-2.0",
                        "ONNX Runtime (Microsoft) · MIT",
                        "Plus Jakarta Sans (Tokotype) · SIL OFL 1.1",
                        "Bricolage Grotesque (Mathieu Triay) · SIL OFL 1.1",
                        "AndroidX & Jetpack Compose (Google) · Apache-2.0",
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp, start = 4.dp),
    )
    HeronCard(content = content)
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, chevron: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- Heron AI ----------------

@Composable
private fun ModelCard(vm: MainViewModel, onImport: () -> Unit) {
    val model by vm.modelState.collectAsState()
    val work by vm.modelDownload.collectAsState()
    val selfTest by vm.selfTest.collectAsState()
    val settings by vm.settings.collectAsState()
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var details by remember { mutableStateOf<DiagnosticReport?>(null) }
    val active = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.tertiary, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("On-device AI", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    active -> "Downloading…"
                    model is ModelState.Ready -> "Installed and working offline"
                    model is ModelState.Loading -> "Loading…"
                    model is ModelState.Failed -> "Couldn't load. Using built-in rules."
                    else -> "Not installed. Using built-in rules."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    when {
        active -> DownloadProgress(work!!)
        model is ModelState.Ready -> {
            val m = model as ModelState.Ready
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("${m.sizeMb} MB", "on device", Modifier.weight(1f))
                MiniStat("${m.msPerDecision} ms", "per decision", Modifier.weight(1f))
            }
        }
        model is ModelState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Getting ready…", style = MaterialTheme.typography.bodyMedium)
        }
        else -> Text(
            "Heron's AI reads notifications the way a person would, for sharper sorting and scam checks. It's about 300 MB, runs fully offline, and Heron tests it on your phone after installing.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!active && work?.state == WorkInfo.State.FAILED) {
        Spacer(Modifier.height(10.dp))
        Status(false, work?.outputData?.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed")
    }

    if (!active && model !is ModelState.Ready) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            active -> OutlinedButton(onClick = vm::cancelDownload, shape = CircleShape) { Text("Cancel") }
            model is ModelState.Ready -> {
                Button(onClick = vm::runSelfTest, enabled = selfTest !is SelfTestState.Running, shape = CircleShape) { Text("Run self-test") }
                TextButton(onClick = vm::deleteModel) { Text("Remove", color = Heron.colors.danger) }
            }
            else -> {
                Button(onClick = { vm.downloadModel(wifiOnly) }, shape = CircleShape) {
                    Text(if (work?.state == WorkInfo.State.FAILED) "Try again" else "Download")
                }
                TextButton(onClick = onImport) { Text("Import files") }
            }
        }
    }
    if (model is ModelState.Ready) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use AI for decisions", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = settings.useModel, onCheckedChange = { v -> vm.updateSettings { it.copy(useModel = v) } })
        }
    }

    when (val t = selfTest) {
        is SelfTestState.Running -> {
            Spacer(Modifier.height(12.dp))
            Text("Testing on this phone… ${t.done}/${t.total}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Meter(if (t.total > 0) t.done.toFloat() / t.total else 0f, MaterialTheme.colorScheme.primary)
        }
        is SelfTestState.Done -> {
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            val r = t.report
            Status(r.errors == 0 && r.wellFormed && r.accuracy >= 0.6f, "Self-test passed ${r.passed} of ${r.total} · ${r.medianMillis} ms each")
            Spacer(Modifier.height(8.dp))
            r.byFeature().forEach { (f, sc) ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                    Meter(sc.first.toFloat() / sc.second, Heron.colors.safe, Modifier.weight(1f), height = 6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${sc.first}/${sc.second}", style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(onClick = { details = r }) { Text("See every test") }
        }
        is SelfTestState.Error -> { Spacer(Modifier.height(10.dp)); Status(false, "Self-test error: ${t.message}") }
        SelfTestState.Idle -> Unit
    }
    details?.let { SelfTestDialog(it) { details = null } }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadProgress(work: WorkInfo) {
    val stage = work.progress.getString(ModelDownloadWorker.KEY_STAGE)
    val done = work.progress.getLong(ModelDownloadWorker.KEY_DONE, 0)
    val total = work.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0)
    val text = when {
        work.state == WorkInfo.State.ENQUEUED && work.runAttemptCount > 0 -> "Connection lost. It'll resume by itself."
        work.state == WorkInfo.State.ENQUEUED -> "Waiting for Wi-Fi…"
        stage == ModelDownloadWorker.STAGE_INSTALLING -> "Installing…"
        stage == ModelDownloadWorker.STAGE_TESTING -> "Testing on this phone…"
        total > 0 -> "${done / (1024 * 1024)} of ${total / (1024 * 1024)} MB"
        else -> "Starting…"
    }
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(10.dp))
    Meter(if (stage == ModelDownloadWorker.STAGE_DOWNLOADING && total > 0) done.toFloat() / total else 0.05f, MaterialTheme.colorScheme.primary, height = 10.dp)
    Spacer(Modifier.height(8.dp))
    Text("Feel free to leave. It keeps going in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SelfTestDialog(r: DiagnosticReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Self-test: ${r.passed}/${r.total}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(r.results) { res ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (res.passed) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null,
                            tint = if (res.passed) Heron.colors.safe else Heron.colors.danger, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${res.case.feature} · ${res.case.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            val d = res.decision
                            Text(
                                if (d == null) "Error: ${res.error}" else "→ ${d.choice} · ${(d.confidence * 100).toInt()}% · ${res.millis} ms",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Status(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = if (ok) Heron.colors.safe else Heron.colors.danger, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- End-to-end check ----------------

@Composable
private fun PipelineTest(vm: MainViewModel) {
    val test by vm.pipelineTest.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.Science, MaterialTheme.colorScheme.primary, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Check that everything works", style = MaterialTheme.typography.titleMedium)
            Text(
                "Heron sends itself a fake scam, a promo and an emergency, then checks each one is handled correctly.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    test?.checks?.let { checks ->
        Spacer(Modifier.height(12.dp))
        checks.forEach { c ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    when { c.ok -> Icons.Rounded.CheckCircle; c.required -> Icons.Rounded.Error; else -> Icons.Rounded.Info },
                    null,
                    tint = when { c.ok -> Heron.colors.safe; c.required -> Heron.colors.danger; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val t = test
    if (t != null && !t.running) {
        Spacer(Modifier.height(8.dp))
        Status(t.allRequiredPassed, if (t.allRequiredPassed) "Everything is working" else "Something needs attention")
    }
    Spacer(Modifier.height(14.dp))
    Button(onClick = vm::runPipelineTest, enabled = t?.running != true, shape = CircleShape) {
        if (t?.running == true) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(if (t == null) "Run check" else "Run again")
    }
}
