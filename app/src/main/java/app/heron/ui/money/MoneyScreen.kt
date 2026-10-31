package app.heron.ui.money

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heron.core.money.BudgetCategory
import app.heron.core.money.Direction
import app.heron.core.money.Fx
import app.heron.data.TransactionRecord
import app.heron.ui.MainViewModel
import app.heron.ui.components.EmptyState
import app.heron.ui.components.HeronCard
import app.heron.ui.components.IconBadge
import app.heron.ui.components.Meter
import app.heron.ui.components.Monogram
import app.heron.ui.components.PageHeader
import app.heron.ui.components.SectionHeader
import app.heron.ui.components.Tag
import app.heron.ui.components.dayLabel
import app.heron.ui.components.icon
import app.heron.ui.components.money
import app.heron.ui.components.timeOf
import app.heron.ui.theme.Heron
import app.heron.ui.theme.categoryColor
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun cat(name: String) = runCatching { BudgetCategory.valueOf(name) }.getOrDefault(BudgetCategory.OTHER)

@Composable
fun MoneyScreen(vm: MainViewModel, startImport: Boolean = false, onImportShown: () -> Unit = {}) {
    val all by vm.transactions.collectAsState()
    val subs by vm.subscriptions.collectAsState()
    val settings by vm.settings.collectAsState()
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TransactionRecord?>(null) }
    var importing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(startImport) { if (startImport) { importing = true; onImportShown() } }

    val zone = ZoneId.systemDefault()
    val month = YearMonth.now().minusMonths(monthOffset.toLong())
    val cur = settings.currency
    val inMonth = all.filter { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month }
    // Everything is totalled in the home currency; foreign amounts are converted (pegs are exact).
    val convertible = inMonth.filter { Fx.supports(it.currency) }
    val debits = convertible.filter { it.direction == Direction.DEBIT.name }
    val spent = debits.sumOf { it.inHome(cur) }
    val income = convertible.filter { it.direction == Direction.CREDIT.name }.sumOf { it.inHome(cur) }
    val byCategory = debits.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.inHome(cur) } }.entries.sortedByDescending { it.value }
    val foreign = inMonth.map { it.currency }.filter { it != cur }.distinct()
    val unknown = inMonth.count { !Fx.supports(it.currency) }
    val flagged = inMonth.filter { it.anomalies.isNotBlank() }
    val days = inMonth.groupBy { dayLabel(it.timestamp) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item {
            PageHeader("Money", eyebrow = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))) {
                IconButton(onClick = { importing = true }) { Icon(Icons.Rounded.History, "Import past transactions") }
                IconButton(onClick = { monthOffset++ }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                IconButton(onClick = { if (monthOffset > 0) monthOffset-- }, enabled = monthOffset > 0) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            HeronCard {
                Text("SPENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(spent, cur), style = MaterialTheme.typography.displayMedium)
                val budget = settings.monthlyBudget
                if (budget > 0) {
                    val frac = (spent / budget).toFloat()
                    val color = when {
                        frac > 1f -> Heron.colors.danger
                        frac > 0.85f -> Heron.colors.caution
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Spacer(Modifier.height(14.dp))
                    Meter(frac, color, height = 10.dp)
                    Spacer(Modifier.height(8.dp))
                    val left = budget - spent
                    val daysLeft = if (monthOffset == 0) month.lengthOfMonth() - LocalDate.now().dayOfMonth else 0
                    Row {
                        Text(
                            if (left >= 0) "${money(left, cur, false)} left" else "${money(-left, cur, false)} over",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (left >= 0) MaterialTheme.colorScheme.onSurface else Heron.colors.danger,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                monthOffset != 0 -> "Budget ${money(budget, cur, false)}"
                                daysLeft == 0 -> "Last day · budget ${money(budget, cur, false)}"
                                daysLeft == 1 -> "1 day to go · budget ${money(budget, cur, false)}"
                                else -> "$daysLeft days to go · budget ${money(budget, cur, false)}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (byCategory.isNotEmpty() && spent > 0) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        byCategory.forEach { (c, amt) ->
                            Box(Modifier.weight((amt / spent).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(categoryColor(cat(c))))
                        }
                    }
                }
                if (income > 0) {
                    Spacer(Modifier.height(14.dp))
                    Text("Income ${money(income, cur, false)}", style = MaterialTheme.typography.labelLarge, color = Heron.colors.safe)
                }
                if (foreign.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(fxNote(foreign.filter { Fx.supports(it) }, cur, unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (flagged.isNotEmpty()) {
            item { SectionHeader("Check these") }
            flagged.forEach { t ->
                item(key = "f${t.id}") {
                    HeronCard(Modifier.padding(bottom = 10.dp), onClick = { editing = t }, color = Heron.colors.cautionContainer, border = false, contentPadding = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Warning, Heron.colors.caution, size = 40.dp, background = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${t.merchant} · ${money(t.amount, t.currency)}", style = MaterialTheme.typography.titleSmall)
                                Text(t.anomalies, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (byCategory.isNotEmpty()) {
            item { SectionHeader("Where it went") }
            item {
                HeronCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    byCategory.forEach { (name, amt) ->
                        val c = cat(name)
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(c.icon(), categoryColor(c), size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text(money(amt, cur, false), style = MaterialTheme.typography.titleSmall)
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Meter((amt / spent).toFloat(), categoryColor(c), Modifier.weight(1f), height = 6.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text((amt / spent * 100).let { if (it > 0 && it < 1) "<1%" else "${it.toInt()}%" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (subs.isNotEmpty()) {
            item {
                val yearly = subs.sumOf { s -> Fx.convert(s.yearlyCost, s.currency, cur) ?: 0.0 }
                SectionHeader("Subscriptions")
                HeronCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Autorenew, MaterialTheme.colorScheme.primary, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${money(yearly, cur, false)} a year", style = MaterialTheme.typography.titleMedium)
                            Text("${subs.size} recurring payments found. Cancel what you've forgotten about.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    subs.forEach { s ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Monogram(s.displayName, MaterialTheme.colorScheme.primary, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${s.period.label.replaceFirstChar { it.uppercase() }} · next ${shortDate(s.nextExpected)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(s.amount, s.currency), style = MaterialTheme.typography.titleSmall)
                                if (s.priceIncreased) Tag("Price up", Heron.colors.caution) else
                                    Text("${money(s.yearlyCost, s.currency, false)}/yr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Transactions") }
        if (inMonth.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.AccountBalanceWallet,
                    "No transactions yet",
                    "Turn on your bank's card alerts and Heron picks them up from now on. Bring in the past from your bank texts or a statement.",
                ) {
                    androidx.compose.material3.Button(onClick = { importing = true }, shape = CircleShape) { Text("Import past transactions") }
                }
            }
        }
        days.forEach { (day, rows) ->
            item(key = "d-$day") {
                Text(day.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                HeronCard(Modifier.padding(bottom = 8.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    rows.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        TransactionRow(t, cur) { editing = t }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (importing) ImportSheet(vm) { importing = false }
    editing?.let { t -> CategorySheet(t, onDismiss = { editing = null }, onPick = { vm.recategorize(t, it); editing = null }, onDelete = { vm.deleteTransaction(t); editing = null }) }
}

@Composable
private fun TransactionRow(t: TransactionRecord, home: String, onClick: () -> Unit) {
    val credit = t.direction == Direction.CREDIT.name
    val c = cat(t.category)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(c.icon(), categoryColor(c), size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.label} · ${timeOf(t.timestamp)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (credit) "+" else "") + money(t.amount, t.currency),
                style = MaterialTheme.typography.titleSmall,
                color = if (credit) Heron.colors.safe else MaterialTheme.colorScheme.onSurface,
            )
            if (t.currency != home) {
                Fx.convert(t.amount, t.currency, home)?.let { v ->
                    Text(
                        (if (Fx.isExact(t.currency, home)) "= " else "≈ ") + money(v, home),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (t.anomalies.isNotBlank()) Text("Check", style = MaterialTheme.typography.labelSmall, color = Heron.colors.caution)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategorySheet(t: TransactionRecord, onDismiss: () -> Unit, onPick: (BudgetCategory) -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(t.merchant, style = MaterialTheme.typography.headlineSmall)
            Text("${money(t.amount, t.currency)} · ${dayLabel(t.timestamp)} ${timeOf(t.timestamp)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.anomalies.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Tag(t.anomalies.lines().first(), Heron.colors.caution, icon = Icons.Rounded.Warning)
            }
            Spacer(Modifier.height(14.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(t.rawText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            Text("CATEGORY FOR ALL ${t.merchant.uppercase()} PAYMENTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BudgetCategory.entries.forEach { c ->
                    val on = c.name == t.category
                    Surface(
                        onClick = { onPick(c) },
                        shape = CircleShape,
                        color = if (on) categoryColor(c).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, if (on) categoryColor(c) else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(c.icon(), null, tint = categoryColor(c), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(c.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDelete) { Text("This isn't a transaction", color = Heron.colors.danger) }
        }
    }
}

/** Amount in the home currency (foreign currencies converted; unknown ones count as 0). */
fun TransactionRecord.inHome(home: String): Double = Fx.convert(amount, currency, home) ?: 0.0

private fun fxNote(currencies: List<String>, home: String, unknown: Int): String {
    val parts = currencies.map { c ->
        val rate = Fx.convert(1.0, c, home) ?: 0.0
        (if (Fx.isExact(c, home)) "1 $c = " else "1 $c ≈ ") + "%.4g".format(rate) + " $home"
    }
    val base = if (parts.isEmpty()) "" else "Converted to $home: " + parts.joinToString(" · ") + "."
    return if (unknown > 0) "$base $unknown in other currencies not included.".trim() else base
}

private fun shortDate(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
