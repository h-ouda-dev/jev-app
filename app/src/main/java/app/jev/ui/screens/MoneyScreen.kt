package app.jev.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jev.core.money.BudgetCategory
import app.jev.core.money.Direction
import app.jev.data.TransactionRecord
import app.jev.ui.MainViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MoneyScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val all by vm.transactions.collectAsState()
    val subs by vm.subscriptions.collectAsState()
    val settings by vm.settings.collectAsState()
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TransactionRecord?>(null) }

    val zone = ZoneId.systemDefault()
    val month = YearMonth.now().minusMonths(monthOffset.toLong())
    val inMonth = all.filter { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month }
    val currency = settings.currency
    val debits = inMonth.filter { it.direction == Direction.DEBIT.name && it.currency == currency }
    val spent = debits.sumOf { it.amount }
    val income = inMonth.filter { it.direction == Direction.CREDIT.name && it.currency == currency }.sumOf { it.amount }
    val otherCurrencies = inMonth.filter { it.currency != currency }.groupBy { it.currency }
    val byCategory = debits.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    val flagged = inMonth.filter { it.anomalies.isNotBlank() }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text("Money", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
            Text(
                "Built from your bank and card notifications. No bank login, nothing uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { monthOffset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (monthOffset > 0) monthOffset-- }, enabled = monthOffset > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Spent", style = MaterialTheme.typography.labelLarge)
                    Text(money(spent, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (settings.monthlyBudget > 0) {
                        val frac = (spent / settings.monthlyBudget).toFloat()
                        LinearProgressIndicator(
                            progress = { frac.coerceIn(0f, 1f) },
                            color = if (frac > 1f) Color(0xFFC62828) else if (frac > 0.8f) Color(0xFFEF6C00) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(frac * 100).toInt()}% of ${money(settings.monthlyBudget, currency)} budget", style = MaterialTheme.typography.labelMedium)
                    }
                    if (income > 0) Text("Income: ${money(income, currency)}", style = MaterialTheme.typography.bodyMedium)
                    otherCurrencies.forEach { (cur, txs) ->
                        Text("Also: ${money(txs.filter { it.direction == Direction.DEBIT.name }.sumOf { it.amount }, cur)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (flagged.isNotEmpty()) {
            item { SectionTitle("Check these (${flagged.size})") }
            items(flagged, key = { "f${it.id}" }) { t ->
                InfoCard("${t.merchant} · ${money(t.amount, t.currency)}", t.anomalies)
            }
        }

        if (byCategory.isNotEmpty()) {
            item { SectionTitle("By category") }
            items(byCategory, key = { "c${it.key}" }) { (cat, amt) ->
                val label = runCatching { BudgetCategory.valueOf(cat).label }.getOrDefault(cat)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(money(amt, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    LinearProgressIndicator(progress = { if (spent > 0) (amt / spent).toFloat() else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
        }

        if (subs.isNotEmpty()) {
            item {
                SectionTitle("Subscriptions")
                val yearly = subs.filter { it.currency == currency }.sumOf { it.yearlyCost }
                Text(
                    "About ${money(yearly, currency)} a year. Cancel anything you forgot about.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(subs, key = { "s${it.merchantKey}${it.currency}" }) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${s.period.label} · next ~${shortDate(s.nextExpected)} · ${s.chargeCount} charges" + if (s.priceIncreased) " · price went up" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.priceIncreased) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(s.amount, s.currency), style = MaterialTheme.typography.bodyMedium)
                        Text("${money(s.yearlyCost, s.currency)}/yr", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item { SectionTitle("Transactions") }
        if (inMonth.isEmpty()) {
            item {
                Text(
                    "No transactions this month. Make sure your bank app's notifications (or SMS alerts) are turned on.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(inMonth, key = { it.id }) { t ->
            TransactionRow(t) { editing = t }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { t ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(t.merchant) },
            text = {
                Column {
                    Text(t.rawText, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text("Category for all ${t.merchant} payments:", style = MaterialTheme.typography.labelLarge)
                    BudgetCategory.entries.forEach { c ->
                        Text(
                            (if (c.name == t.category) "● " else "○ ") + c.label,
                            modifier = Modifier.fillMaxWidth().clickable { vm.recategorize(t, c); editing = null }.padding(vertical = 6.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { vm.deleteTransaction(t); editing = null }) { Text("Not a transaction") } },
        )
    }
}

@Composable
private fun TransactionRow(t: TransactionRecord, onClick: () -> Unit) {
    val credit = t.direction == Direction.CREDIT.name
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cat = runCatching { BudgetCategory.valueOf(t.category).label }.getOrDefault(t.category)
            Text("$cat · ${shortTime(t.timestamp)}" + (t.cardHint?.let { " · ••$it" } ?: ""), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (credit) "+" else "−") + money(t.amount, t.currency),
            color = if (credit) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun shortDate(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
