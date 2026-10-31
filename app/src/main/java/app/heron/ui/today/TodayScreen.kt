package app.heron.ui.today

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.WorkInfo
import app.heron.R
import app.heron.core.money.BudgetCategory
import app.heron.core.money.Direction
import app.heron.core.scam.ScamVerdict
import app.heron.core.triage.TriageBucket
import app.heron.ml.ModelState
import app.heron.ui.MainViewModel
import app.heron.ui.Tab
import app.heron.ui.components.AppIcon
import app.heron.ui.money.inHome
import app.heron.ui.components.HeroCard
import app.heron.ui.components.HeronCard
import app.heron.ui.components.IconBadge
import app.heron.ui.components.ListRow
import app.heron.ui.components.Meter
import app.heron.ui.components.Ring
import app.heron.ui.components.SectionHeader
import app.heron.ui.components.Stat
import app.heron.ui.components.Tag
import app.heron.ui.components.greeting
import app.heron.ui.components.icon
import app.heron.ui.components.isToday
import app.heron.ui.components.money
import app.heron.ui.components.nextDigestLabel
import app.heron.ui.components.relativeTime
import app.heron.ui.theme.Heron
import app.heron.ui.theme.bucketColor
import app.heron.ui.theme.categoryColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(vm: MainViewModel, openTab: (Tab) -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val all by vm.notifications.collectAsState()
    val txs by vm.transactions.collectAsState()
    val settings by vm.settings.collectAsState()
    val model by vm.modelState.collectAsState()
    val download by vm.modelDownload.collectAsState()
    var access by remember { mutableStateOf(vm.hasNotificationAccess()) }
    LifecycleResumeEffect(Unit) {
        access = vm.hasNotificationAccess()
        vm.reconcileWithShade()
        onPauseOrDispose { }
    }

    val today = all.filter { isToday(it.postedAt) && it.packageName != context.packageName }
    fun count(b: TriageBucket) = today.count { it.bucket == b.name }
    // Only what still needs you: opened, dismissed or cleared notifications drop off.
    val urgent = all.filter { it.bucket == TriageBucket.URGENT.name && it.handledAt == null && it.packageName != context.packageName }
        .filter { System.currentTimeMillis() - it.postedAt < 2 * 86_400_000L }
    val threats = today.filter { it.scamVerdict != null && it.scamVerdict != ScamVerdict.SAFE.name }
    val checked = today.count { it.scamVerdict != null }

    val zone = ZoneId.systemDefault()
    val month = YearMonth.now()
    val monthTx = txs.filter { YearMonth.from(java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month }
    val spent = monthTx.filter { it.direction == Direction.DEBIT.name }.sumOf { it.inHome(settings.currency) }
    val topCats = monthTx.filter { it.direction == Direction.DEBIT.name }.groupBy { it.category }
        .mapValues { e -> e.value.sumOf { it.inHome(settings.currency) } }.entries.sortedByDescending { it.value }.take(3)
    val flags = monthTx.count { it.anomalies.isNotBlank() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Image(painterResource(R.drawable.heron_mark), contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Text("Heron", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = openSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
        }
        item {
            Column(Modifier.padding(top = 18.dp, bottom = 20.dp)) {
                Text(
                    DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(LocalDate.now()).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(greeting(), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        !access -> "Heron is paused. Turn on notification access to begin."
                        threats.isNotEmpty() -> "${threats.size} ${if (threats.size == 1) "scam" else "scams"} stopped today. Everything else is in hand."
                        urgent.isNotEmpty() -> "${urgent.size} ${if (urgent.size == 1) "thing needs" else "things need"} you. The rest can wait."
                        today.isEmpty() -> "All quiet so far. Heron is watching."
                        else -> "All calm. Nothing needs you right now."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!access) {
            item {
                HeronCard(color = Heron.colors.cautionContainer, border = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.NotificationsPaused, Heron.colors.caution, size = 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Notification access is off", style = MaterialTheme.typography.titleSmall)
                            Text("Heron can't sort or protect anything yet.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        shape = CircleShape, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Turn on") }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        item {
            HeroCard(Modifier.clickable { openTab(Tab.INBOX) }) {
                val c = Heron.colors
                Text("HANDLED TODAY", style = MaterialTheme.typography.labelMedium, color = c.onHeroSoft)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${today.size}", style = MaterialTheme.typography.displayLarge, color = c.onHero)
                    Spacer(Modifier.width(10.dp))
                    Text("notifications", style = MaterialTheme.typography.titleMedium, color = c.onHeroSoft, modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("${count(TriageBucket.URGENT)}", "Urgent", Color3.urgent, Modifier.weight(1f), valueColor = c.onHero)
                    Stat("${count(TriageBucket.CAN_WAIT)}", "Later", Color3.later, Modifier.weight(1f), valueColor = c.onHero)
                    Stat("${count(TriageBucket.DIGEST)}", "Digest", Color3.digest, Modifier.weight(1f), valueColor = c.onHero)
                    Stat("${count(TriageBucket.SILENCE)}", "Muted", Color3.muted, Modifier.weight(1f), valueColor = c.onHero)
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = c.onHero.copy(alpha = 0.15f))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nextDigestLabel(settings.digestTimes)?.let { "Next digest at $it" } ?: "Digest is off",
                        style = MaterialTheme.typography.labelLarge, color = c.onHero, modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onHeroSoft)
                }
            }
        }

        val modelIdle = model is ModelState.NotInstalled && download?.state != WorkInfo.State.RUNNING && download?.state != WorkInfo.State.ENQUEUED
        if (access && modelIdle) {
            item {
                Spacer(Modifier.height(14.dp))
                HeronCard(onClick = openSettings, color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f), border = false, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.tertiary, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Make Heron smarter", style = MaterialTheme.typography.titleSmall)
                            Text("Add on-device AI. Free, private, one download.", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            }
        }

        if (urgent.isNotEmpty()) {
            item { SectionHeader("Needs you", action = "See all", onAction = { openTab(Tab.INBOX) }) }
            item {
                HeronCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    urgent.take(3).forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ListRow(
                            title = r.sender ?: r.title,
                            subtitle = r.text,
                            leading = { AppIcon(r.packageName, r.sender ?: r.appLabel, fallbackColor = bucketColor(TriageBucket.URGENT)) },
                            trailing = { Text(relativeTime(r.postedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { vm.openInApp(context, r) },
                            subtitleMaxLines = 1,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Shield", action = "Open", onAction = { openTab(Tab.SHIELD) }) }
        item {
            val bad = threats.isNotEmpty()
            HeronCard(
                onClick = { openTab(Tab.SHIELD) },
                color = if (bad) Heron.colors.dangerContainer else Heron.colors.safeContainer,
                border = false,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(if (bad) Icons.Rounded.GppBad else Icons.Rounded.GppGood, if (bad) Heron.colors.danger else Heron.colors.safe, size = 48.dp,
                        background = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (bad) "${threats.size} ${if (threats.size == 1) "threat" else "threats"} caught today" else "No threats today",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (bad) "Don't reply, tap links or pay. Heron has flagged ${if (threats.size == 1) "it" else "them"} for you."
                            else if (checked > 0) "Heron checked $checked ${if (checked == 1) "message" else "messages"} for scams."
                            else "Every message you receive gets an instant red-flag check.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                threats.firstOrNull()?.let { t ->
                    Spacer(Modifier.height(14.dp))
                    HeronCard(color = MaterialTheme.colorScheme.surfaceContainerLowest, border = false, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, tint = Heron.colors.danger, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t.sender ?: t.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(relativeTime(t.postedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(t.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item { SectionHeader("Money", action = "Details", onAction = { openTab(Tab.MONEY) }) }
        item {
            HeronCard(onClick = { openTab(Tab.MONEY) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val budget = settings.monthlyBudget
                    val frac = if (budget > 0) (spent / budget).toFloat() else 0f
                    val ringColor = when {
                        frac > 1f -> Heron.colors.danger
                        frac > 0.85f -> Heron.colors.caution
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Ring(if (budget > 0) frac else 0f, ringColor, size = 76.dp, stroke = 9.dp) {
                        Text(if (budget > 0) "${(frac * 100).toInt()}%" else "–", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Spent in ${month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(money(spent, settings.currency, decimals = false), style = MaterialTheme.typography.headlineMedium)
                        if (budget > 0) {
                            val left = budget - spent
                            val daysLeft = month.lengthOfMonth() - LocalDate.now().dayOfMonth
                            Text(
                                if (left >= 0) "${money(left, settings.currency, false)} left · " + when (daysLeft) { 0 -> "last day"; 1 -> "1 day to go"; else -> "$daysLeft days to go" } else "${money(-left, settings.currency, false)} over budget",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (left >= 0) MaterialTheme.colorScheme.onSurfaceVariant else Heron.colors.danger,
                            )
                        }
                    }
                }
                if (topCats.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    topCats.forEach { (name, amt) ->
                        val cat = runCatching { BudgetCategory.valueOf(name) }.getOrDefault(BudgetCategory.OTHER)
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(cat.icon(), categoryColor(cat), size = 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(cat.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(money(amt, settings.currency, false), style = MaterialTheme.typography.labelLarge)
                                }
                                Spacer(Modifier.height(4.dp))
                                Meter(if (spent > 0) (amt / spent).toFloat() else 0f, categoryColor(cat), height = 5.dp)
                            }
                        }
                    }
                }
                if (flags > 0) {
                    Spacer(Modifier.height(12.dp))
                    Tag("$flags ${if (flags == 1) "charge" else "charges"} to check", Heron.colors.caution, icon = Icons.Rounded.Warning)
                }
                if (monthTx.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Spending appears here as your bank's alerts arrive.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Everything Heron sees stays on this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Bucket colours as they appear on the dark hero card (always the dark-theme tones). */
private object Color3 {
    val urgent = androidx.compose.ui.graphics.Color(0xFFFF8A80)
    val later = androidx.compose.ui.graphics.Color(0xFF7FD3CB)
    val digest = androidx.compose.ui.graphics.Color(0xFFB3A9FF)
    val muted = androidx.compose.ui.graphics.Color(0xFF9AA7AD)
}

