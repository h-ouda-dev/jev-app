package app.jev.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.jev.ml.ModelState
import app.jev.ui.MainViewModel

private val DIGEST_OPTIONS = listOf(8 * 60, 10 * 60 + 30, 12 * 60 + 30, 15 * 60, 18 * 60 + 30, 21 * 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val model by vm.modelState.collectAsState()
    val rules by vm.senderRules.collectAsState()
    var vipText by remember(s.vipNames) { mutableStateOf(s.vipNames.joinToString(", ")) }
    var budgetText by remember(s.monthlyBudget) { mutableStateOf(if (s.monthlyBudget > 0) "%.0f".format(s.monthlyBudget) else "") }
    var currencyText by remember(s.currency) { mutableStateOf(s.currency) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importModel(uris)
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))

        SectionTitle("Permissions")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }) { Text("Jev's alerts") }
        }

        SectionTitle("Triage")
        ToggleRow(
            "Hide digest & silenced notifications",
            "Off = shadow mode: Jev only labels. On = digest and silenced notifications leave your shade (they stay in Jev's inbox). Calls, alarms and likely scams are never hidden.",
            s.actOnNotifications,
        ) { v -> vm.updateSettings { it.copy(actOnNotifications = v) } }
        OutlinedTextField(
            value = vipText,
            onValueChange = { vipText = it },
            label = { Text("Important people (comma separated)") },
            supportingText = { Text("Always urgent, never silenced. Match the name shown in notifications.") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        TextButton(
            onClick = { vm.updateSettings { it.copy(vipNames = vipText.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) } },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Save people") }

        Text("Digest times", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DIGEST_OPTIONS.forEach { m ->
                val on = m in s.digestTimes
                FilterChip(
                    selected = on,
                    onClick = { vm.updateSettings { it.copy(digestTimes = if (on) it.digestTimes - m else it.digestTimes + m) } },
                    label = { Text("%02d:%02d".format(m / 60, m % 60)) },
                )
            }
        }
        if (rules.isNotEmpty()) {
            Text("Learned rules (tap to forget)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            rules.forEach { r ->
                TextButton(onClick = { vm.forgetRule(r) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("${r.label} → ${r.bucket.lowercase().replace('_', ' ')}")
                }
            }
        }

        SectionTitle("Scam shield")
        ToggleRow("Warn me about suspicious messages", "Posts an alert with the red-flag checklist.", s.scamAlerts) { v ->
            vm.updateSettings { it.copy(scamAlerts = v) }
        }

        SectionTitle("Money")
        ToggleRow("Flag unusual charges", "Duplicates, unusually large payments and subscription price rises.", s.spendAlerts) { v ->
            vm.updateSettings { it.copy(spendAlerts = v) }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = budgetText,
                onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly budget") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = currencyText,
                onValueChange = { currencyText = it.uppercase().take(3) },
                label = { Text("Currency") },
                modifier = Modifier.weight(0.6f),
            )
        }
        TextButton(
            onClick = { vm.updateSettings { it.copy(monthlyBudget = budgetText.toDoubleOrNull() ?: 0.0, currency = currencyText.ifBlank { "QAR" }) } },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Save budget") }

        SectionTitle("On-device model")
        ToggleRow("Use the Jev model", "When off (or no model installed) Jev uses its built-in rules.", s.useModel) { v ->
            vm.updateSettings { it.copy(useModel = v) }
        }
        Text(
            when (val m = model) {
                ModelState.NotInstalled -> "No model installed: using built-in rules. Import model.onnx + tokenizer.json (and optionally jev_config.json). See the README for how to export ModernBERT-JEV."
                ModelState.Loading -> "Loading model…"
                is ModelState.Ready -> "Model ready · ${m.sizeMb} MB · ~${m.msPerDecision} ms per 4-way decision\n${m.details}"
                is ModelState.Failed -> "Model failed to load: ${m.message}. Using built-in rules."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import model files") }
            if (model !is ModelState.NotInstalled) OutlinedButton(onClick = { vm.deleteModel() }) { Text("Remove") }
        }

        SectionTitle("Privacy")
        Text(
            "Jev has no internet permission. Notifications, transactions and the model stay in the app's private storage. History older than ${s.keepDays} days is deleted automatically.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(onClick = { vm.clearHistory() }, modifier = Modifier.padding(16.dp)) { Text("Delete all history") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
