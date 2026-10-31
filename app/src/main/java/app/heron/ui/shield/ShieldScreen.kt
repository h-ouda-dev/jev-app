package app.heron.ui.shield

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heron.core.scam.RedFlag
import app.heron.core.scam.ScamReport
import app.heron.core.scam.ScamVerdict
import app.heron.ui.MainViewModel
import app.heron.ui.components.AppIcon
import app.heron.ui.components.EmptyState
import app.heron.ui.components.HeronCard
import app.heron.ui.components.IconBadge
import app.heron.ui.components.PageHeader
import app.heron.ui.components.SectionHeader
import app.heron.ui.components.Tag
import app.heron.ui.components.relativeTime
import app.heron.ui.theme.Heron
import app.heron.ui.theme.verdictColor
import app.heron.ui.theme.verdictContainer

fun ScamVerdict.icon(): ImageVector = when (this) {
    ScamVerdict.SAFE -> Icons.Rounded.GppGood
    ScamVerdict.SUSPICIOUS -> Icons.Rounded.GppMaybe
    ScamVerdict.LIKELY_SCAM -> Icons.Rounded.GppBad
}

@Composable
fun ShieldScreen(vm: MainViewModel, incoming: String?, onIncomingConsumed: () -> Unit) {
    val context = LocalContext.current
    val report by vm.checkResult.collectAsState()
    val warnings by vm.scamWarnings.collectAsState()
    val all by vm.notifications.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val threats = warnings.filter { it.packageName != context.packageName }
    val checkedCount = all.count { it.scamVerdict != null && it.packageName != context.packageName }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            text = incoming
            onIncomingConsumed()
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item { PageHeader("Shield", eyebrow = "Scam protection") }
        item {
            Spacer(Modifier.height(12.dp))
            val bad = threats.isNotEmpty()
            HeronCard(color = if (bad) Heron.colors.cautionContainer else Heron.colors.safeContainer, border = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        Icons.Rounded.VerifiedUser, if (bad) Heron.colors.caution else Heron.colors.safe, size = 52.dp,
                        background = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("You're protected", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (checkedCount > 0) "$checkedCount messages checked · ${threats.size} ${if (threats.size == 1) "threat" else "threats"} caught"
                            else "Every message is checked the moment it arrives.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Check something yourself") }
        item {
            HeronCard {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; vm.clearCheck() },
                    placeholder = { Text("Paste a message, email or link…") },
                    minLines = 3,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    trailingIcon = if (text.isNotEmpty()) {
                        { IconButton(onClick = { text = ""; vm.clearCheck() }) { Icon(Icons.Rounded.Close, "Clear") } }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { text = it; vm.check(it); focus.clearFocus() } },
                        shape = CircleShape, modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste")
                    }
                    Button(onClick = { vm.check(text); focus.clearFocus() }, enabled = text.isNotBlank(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                        Text("Check")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Tip: select text in any app and tap “Check with Heron”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            AnimatedVisibility(report != null, enter = fadeIn() + slideInVertically { it / 6 }) {
                report?.let { Column { Spacer(Modifier.height(14.dp)); ReportCard(it) } }
            }
        }

        item { SectionHeader("Recent threats") }
        if (threats.isEmpty()) {
            item { EmptyState(Icons.Rounded.GppGood, "Nothing suspicious yet", "When Heron catches a scam, it shows up here with the reasons why.", tint = Heron.colors.safe) }
        } else {
            item {
                HeronCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    threats.take(20).forEachIndexed { i, w ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        val verdict = ScamVerdict.valueOf(w.scamVerdict ?: "SAFE")
                        val flags = w.scamFlags?.split(',')?.mapNotNull { n -> RedFlag.entries.firstOrNull { it.name == n } }.orEmpty()
                        Surface(onClick = { text = w.text; vm.check(w.text) }, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
                                AppIcon(w.packageName, w.sender ?: w.appLabel, size = 44.dp, fallbackColor = verdictColor(verdict))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(w.sender ?: w.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(relativeTime(w.postedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(w.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Tag(verdict.label, verdictColor(verdict), icon = verdict.icon())
                                        if (flags.isNotEmpty()) Tag("${flags.size} red flags", MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ReportCard(r: ScamReport) {
    val color = verdictColor(r.verdict)
    HeronCard(contentPadding = PaddingValues(0.dp)) {
        Surface(color = verdictContainer(r.verdict)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(r.verdict.icon(), color, size = 52.dp, background = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.75f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.verdict.label, style = MaterialTheme.typography.headlineSmall, color = color)
                    Text(
                        when (r.verdict) {
                            ScamVerdict.LIKELY_SCAM -> "Don't tap links, pay or share any code. Contact the company through its official app instead."
                            ScamVerdict.SUSPICIOUS -> "Some warning signs. Check with the sender through a channel you already trust."
                            ScamVerdict.SAFE -> "No red flags found. Stay alert anyway; scammers keep changing tactics."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("RED-FLAG CHECKLIST", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            r.checks.sortedByDescending { it.raised }.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    val tint = if (c.raised) Heron.colors.danger else Heron.colors.safe
                    IconBadge(if (c.raised) Icons.Rounded.Close else Icons.Rounded.Check, tint, size = 26.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.flag.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (c.raised) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (c.raised) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (c.raised && c.evidence.isNotEmpty()) {
                            Text(
                                c.evidence.take(3).joinToString("  ·  ") { "“$it”" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (r.links.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("LINKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                r.links.forEach { l ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        IconBadge(Icons.Rounded.Link, if (l.suspicious) Heron.colors.danger else Heron.colors.safe, size = 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(l.host, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (l.suspicious) l.reasons.joinToString(" · ") else "Nothing wrong with this address",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (l.suspicious) Heron.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
