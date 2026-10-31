package app.jev.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jev.core.scam.RedFlag
import app.jev.core.scam.ScamReport
import app.jev.core.scam.ScamVerdict
import app.jev.ui.MainViewModel
import app.jev.ui.theme.verdictColor

@Composable
fun ShieldScreen(vm: MainViewModel, incoming: String?, onIncomingConsumed: () -> Unit, modifier: Modifier = Modifier) {
    val report by vm.checkResult.collectAsState()
    val warnings by vm.scamWarnings.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(incoming) {
        if (incoming != null) {
            text = incoming
            onIncomingConsumed()
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text("Scam shield", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
            Text(
                "Every incoming message is checked automatically. You can also paste a text or link here, share it to Jev, or select text in any app and choose \"Check with Jev\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; vm.clearCheck() },
                label = { Text("Message or link") },
                minLines = 3,
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { text = it; vm.check(it) } }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.check(text) }, enabled = text.isNotBlank()) { Text("Check") }
                OutlinedButton(onClick = { text = ""; vm.clearCheck() }) { Text("Clear") }
            }
        }
        report?.let { r -> item { ReportCard(r) } }

        item { SectionTitle("Recent warnings") }
        if (warnings.isEmpty()) {
            item {
                Text(
                    "No suspicious messages so far.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(warnings, key = { it.id }) { w ->
            val verdict = ScamVerdict.valueOf(w.scamVerdict ?: "SAFE")
            Column(
                Modifier.fillMaxWidth().clickable { text = w.text; vm.check(w.text) }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(verdict.label, verdictColor(verdict))
                    Text("${w.appLabel} · ${w.sender ?: w.title} · ${shortTime(w.postedAt)}", style = MaterialTheme.typography.labelMedium)
                }
                Text(w.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val flags = w.scamFlags?.split(',')?.mapNotNull { n -> RedFlag.entries.firstOrNull { it.name == n }?.title }.orEmpty()
                if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = verdictColor(verdict))
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun ReportCard(r: ScamReport) {
    val color = verdictColor(r.verdict)
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.verdict.label, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Text(
                when (r.verdict) {
                    ScamVerdict.LIKELY_SCAM -> "Don't tap links, pay, or share any code. If it claims to be your bank or a courier, contact them using the number on their official app or card."
                    ScamVerdict.SUSPICIOUS -> "Some red flags. Verify with the sender through a channel you already trust before acting."
                    ScamVerdict.SAFE -> "No red flags found. Stay alert anyway: scammers change tactics."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            r.checks.forEach { c ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        if (c.raised) Icons.Filled.Error else Icons.Filled.CheckCircle,
                        contentDescription = if (c.raised) "Red flag" else "OK",
                        tint = if (c.raised) verdictColor(ScamVerdict.LIKELY_SCAM) else Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(c.flag.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (c.raised) FontWeight.SemiBold else FontWeight.Normal)
                        if (c.raised && c.evidence.isNotEmpty()) {
                            Text(
                                c.evidence.take(3).joinToString(" · ") { "\"$it\"" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text("${(c.probability * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (r.links.isNotEmpty()) {
                HorizontalDivider()
                r.links.forEach { l ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                        Column {
                            Text(l.host, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (l.suspicious) l.reasons.joinToString(" · ") else "No problems found with this address",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (l.suspicious) verdictColor(ScamVerdict.LIKELY_SCAM) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
