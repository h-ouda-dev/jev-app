package app.heron.ui.money

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heron.BuildConfig
import app.heron.ui.ImportState
import app.heron.ui.MainViewModel
import app.heron.ui.components.HeronCard
import app.heron.ui.components.IconBadge
import app.heron.ui.components.Meter
import app.heron.ui.theme.Heron
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PERIODS = listOf(1, 3, 6, 12)

/** "Bring in your history": past bank SMS or a CSV statement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val state by vm.importState.collectAsState()
    var months by remember { mutableIntStateOf(3) }
    var smsDenied by remember { mutableStateOf(false) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.importSms(months) else smsDenied = true
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    val running = state is ImportState.Running
    val importedCount by vm.importedCount.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!running) { vm.resetImport(); onDismiss() } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Bring in your history", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Heron sorts your past purchases into categories and spots subscriptions straight away, with no bank login.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                is ImportState.Running -> Progress(s)
                is ImportState.Done -> Result(s) { vm.resetImport(); onDismiss() }
                is ImportState.Failed -> {
                    FailedCard(s.message)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = vm::resetImport, shape = CircleShape) { Text("Try again") }
                }
                ImportState.Idle -> {
                    if (BuildConfig.SMS_IMPORT) {
                        HeronCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Rounded.Sms, MaterialTheme.colorScheme.primary, size = 44.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Bank text messages", style = MaterialTheme.typography.titleMedium)
                                    Text("Card and account alerts your bank has texted you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("HOW FAR BACK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PERIODS.forEach { m ->
                                    val on = m == months
                                    Surface(
                                        onClick = { months = m },
                                        shape = CircleShape,
                                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            if (m == 12) "1 yr" else "$m mo",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { if (vm.canReadSms()) vm.importSms(months) else smsPermission.launch(Manifest.permission.READ_SMS) },
                                shape = CircleShape, modifier = Modifier.fillMaxWidth().height(50.dp),
                            ) { Text("Import messages") }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Read on this phone only. Heron keeps the transactions and ignores every other message.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (smsDenied) {
                                Spacer(Modifier.height(8.dp))
                                Text("Heron needs permission to read SMS for this. You can allow it in the app's settings.", style = MaterialTheme.typography.bodySmall, color = Heron.colors.danger)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    HeronCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Description, MaterialTheme.colorScheme.tertiary, size = 44.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Bank statement (Excel or CSV)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Download your statement as Excel (.xlsx) or CSV from online banking or a statement email, then pick it here. Any bank, any layout.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = {
                                csvPicker.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*",
                                    ),
                                )
                            },
                            shape = CircleShape, modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) { Text("Choose file") }
                    }
                    if (importedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete $importedCount imported transactions", color = Heron.colors.danger)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) ConfirmDeleteImported(importedCount, onConfirm = { vm.deleteImportedTransactions(); confirmDelete = false }, onDismiss = { confirmDelete = false })
}

@Composable
private fun ConfirmDeleteImported(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete imported transactions?") },
        text = { Text("The $count transactions imported from text messages and statements are removed. Ones Heron saw live stay. You can import again any time.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Delete", color = Heron.colors.danger) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Progress(s: ImportState.Running) {
    HeronCard {
        Text(s.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(s.stage, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        Meter(s.progress ?: 0.08f, MaterialTheme.colorScheme.primary, height = 10.dp)
    }
}

@Composable
private fun Result(s: ImportState.Done, onDone: () -> Unit) {
    val sum = s.summary
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    fun d(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    HeronCard(color = if (sum.found > 0) Heron.colors.safeContainer else MaterialTheme.colorScheme.surfaceContainerLowest, border = sum.found == 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CheckCircle, Heron.colors.safe, size = 44.dp, background = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (sum.found > 0) "Added ${sum.found} transactions" else "No new transactions found",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(s.label, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (sum.found > 0) {
            Line("${sum.merchants} merchants sorted into categories")
            if (sum.from != null && sum.to != null) Line("From ${d(sum.from!!)} to ${d(sum.to!!)}")
        }
        Line("${sum.scanned} ${if (s.label.startsWith("Bank statement")) "rows" else "messages"} checked")
        sum.note?.let { Line(it) }
        if (sum.duplicates > 0) Line("${sum.duplicates} already in Heron, skipped")
        if (sum.found == 0) Line("Tip: make sure your bank sends card alerts by SMS, or try a CSV statement.")
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDone, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("See my spending") }
}

@Composable
private fun Line(text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedCard(message: String) {
    HeronCard(color = Heron.colors.dangerContainer, border = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Error, null, tint = Heron.colors.danger)
            Spacer(Modifier.width(10.dp))
            Text("Couldn't import: $message", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
