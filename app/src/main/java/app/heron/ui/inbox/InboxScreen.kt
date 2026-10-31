package app.heron.ui.inbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heron.core.scam.ScamVerdict
import app.heron.core.triage.TriageBucket
import app.heron.data.NotificationRecord
import app.heron.ui.MainViewModel
import app.heron.ui.components.AppIcon
import app.heron.ui.components.EmptyState
import app.heron.ui.components.HeronCard
import app.heron.ui.components.IconBadge
import app.heron.ui.components.PageHeader
import app.heron.ui.components.ProbabilityRow
import app.heron.ui.components.Tag
import app.heron.ui.components.blurb
import app.heron.ui.components.dayLabel
import app.heron.ui.components.icon
import app.heron.ui.components.relativeTime
import app.heron.ui.components.timeOf
import app.heron.ui.theme.Heron
import app.heron.ui.theme.bucketColor
import app.heron.ui.theme.verdictColor

@Composable
fun InboxScreen(vm: MainViewModel, openShield: (String) -> Unit) {
    val context = LocalContext.current
    val all by vm.notifications.collectAsState()
    val settings by vm.settings.collectAsState()
    val items = all.filter { it.packageName != context.packageName }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NotificationRecord?>(null) }
    val shown = if (filter == null) items else items.filter { it.bucket == filter }
    val grouped = shown.groupBy { dayLabel(it.postedAt) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageHeader("Inbox", Modifier.padding(horizontal = 20.dp), eyebrow = "Sorted by Heron") }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("All", items.size, null, filter == null) { filter = null }
                TriageBucket.entries.forEach { b ->
                    FilterPill(b.label, items.count { it.bucket == b.name }, b, filter == b.name) { filter = b.name }
                }
            }
        }
        if (!settings.actOnNotifications && items.isNotEmpty()) {
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Visibility, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Preview mode: Heron labels but doesn't hide anything yet. Tap any item to teach it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                val b = filter?.let { TriageBucket.valueOf(it) }
                EmptyState(
                    icon = b?.icon() ?: Icons.Rounded.Inbox,
                    title = if (items.isEmpty()) "Nothing here yet" else "Nothing in ${b?.label ?: "this view"}",
                    body = if (items.isEmpty()) "Notifications appear here as Heron sorts them." else b?.blurb() ?: "",
                    tint = b?.let { bucketColor(it) } ?: MaterialTheme.colorScheme.primary,
                )
            }
        }
        grouped.forEach { (day, rows) ->
            item(key = "h-$day") {
                Text(
                    day.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                )
            }
            item(key = "g-$day") {
                HeronCard(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        NotificationRow(r) { selected = r }
                    }
                }
            }
        }
    }

    selected?.let { r ->
        DecisionSheet(
            r,
            onDismiss = { selected = null },
            onTeach = { vm.teach(r, it); selected = null },
            onCheckScam = { openShield(r.text); selected = null },
        )
    }
}

@Composable
private fun FilterPill(label: String, count: Int, bucket: TriageBucket?, selected: Boolean, onClick: () -> Unit) {
    val color = bucket?.let { bucketColor(it) } ?: MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bucket != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(7.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotificationRow(r: NotificationRecord, onClick: () -> Unit) {
    val bucket = TriageBucket.valueOf(r.bucket)
    val verdict = r.scamVerdict?.let { ScamVerdict.valueOf(it) }?.takeIf { it != ScamVerdict.SAFE }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
        AppIcon(r.packageName, r.appLabel, size = 44.dp, fallbackColor = bucketColor(bucket))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.sender ?: r.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(r.postedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Text(r.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(bucket.label, bucketColor(bucket), icon = bucket.icon())
                if (verdict != null) Tag(verdict.label, verdictColor(verdict), icon = Icons.Rounded.GppBad)
                Text(r.appLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecisionSheet(r: NotificationRecord, onDismiss: () -> Unit, onTeach: (TriageBucket) -> Unit, onCheckScam: () -> Unit) {
    val bucket = TriageBucket.valueOf(r.bucket)
    val probs = r.probabilities.split(';').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { TriageBucket.valueOf(k) to v.toFloat() }.getOrNull()
    }
    val verdict = r.scamVerdict?.let { ScamVerdict.valueOf(it) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(r.packageName, r.appLabel, size = 48.dp, fallbackColor = bucketColor(bucket))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.sender ?: r.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.appLabel} · ${dayLabel(r.postedAt)} ${timeOf(r.postedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(r.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            if (verdict != null && verdict != ScamVerdict.SAFE) {
                Spacer(Modifier.height(12.dp))
                Surface(onClick = onCheckScam, shape = MaterialTheme.shapes.medium, color = Heron.colors.dangerContainer) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GppBad, null, tint = Heron.colors.danger)
                        Spacer(Modifier.width(10.dp))
                        Text("${verdict.label}: see the red flags", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("HERON'S CALL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(bucket.label, bucketColor(bucket), icon = bucket.icon(), filled = true)
                Spacer(Modifier.width(10.dp))
                Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            probs.sortedByDescending { it.second }.forEach { (b, p) -> ProbabilityRow(b.label, p, bucketColor(b), b == bucket) }
            Spacer(Modifier.height(22.dp))
            Text("ALWAYS TREAT ${(r.sender ?: r.title).uppercase()} AS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            TriageBucket.entries.forEach { b ->
                val on = b == bucket && r.decidedBy == "YOU"
                Surface(
                    onClick = { onTeach(b) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (on) bucketColor(b).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, if (on) bucketColor(b) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(b.icon(), bucketColor(b), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(b.blurb(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text("Done") }
        }
    }
}
