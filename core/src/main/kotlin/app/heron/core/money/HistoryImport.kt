package app.heron.core.money

import app.heron.core.model.Decision
import app.heron.core.model.DecisionSource

/** One raw message from the past (an SMS, an email, a notification). */
data class RawMessage(val source: String, val body: String, val timestamp: Long)

data class ImportedTransaction(val tx: ParsedTransaction, val category: BudgetCategory, val confidence: Float, val anomalies: List<Anomaly>)

data class ImportSummary(
    val scanned: Int,
    val found: Int,
    val duplicates: Int,
    val merchants: Int,
    val sources: Int,
    val from: Long?,
    val to: Long?,
)

/**
 * Turns a pile of historical messages (or statement rows) into categorised
 * transactions. Built for bulk: each distinct merchant is categorised once
 * (rules + learned choices + the model) and reused, and anomalies are
 * computed in time order so the history reads like it happened live.
 */
class HistoryImporter(private val categorizer: Categorizer) {

    fun fromMessages(messages: List<RawMessage>): List<ParsedTransaction> =
        messages.mapNotNull { m ->
            // Bank SMS always say what happened (spent, debited, credited…), so the parser needs no sender trust.
            val src = if (m.source.any { it.isLetter() }) "sms-sender:${m.source}" else "sms:${m.source}"
            TransactionParser.parse(m.body, src, m.timestamp)
        }

    fun fromStatement(rows: List<StatementRow>, sourceLabel: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<ParsedTransaction> =
        rows.map { r ->
            ParsedTransaction(
                amount = r.amount,
                currency = r.currency,
                merchant = cleanDescription(r.description),
                direction = r.direction,
                cardHint = null,
                sourcePackage = "statement:$sourceLabel",
                rawText = r.description,
                timestamp = StatementCsv.toEpochMillis(r.date, zone),
            )
        }

    /**
     * Categorises and analyses [txs] against [existing] history.
     * [isDuplicate] lets the caller skip what's already stored.
     * [onProgress] reports (merchantsDone, merchantsTotal) while categorising.
     */
    fun process(
        txs: List<ParsedTransaction>,
        existing: List<TxPoint>,
        learned: Map<String, BudgetCategory>,
        isDuplicate: (ParsedTransaction) -> Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<List<ImportedTransaction>, Int> {
        val sorted = txs.sortedBy { it.timestamp }
        // In-batch duplicates: the same alert can arrive as SMS and push, or a statement can overlap.
        val unique = mutableListOf<ParsedTransaction>()
        var duplicates = 0
        for (t in sorted) {
            val dupInBatch = unique.any { u ->
                u.merchantKey == t.merchantKey && kotlin.math.abs(u.amount - t.amount) < 0.005 &&
                    kotlin.math.abs(u.timestamp - t.timestamp) < 5 * 60_000L && u.direction == t.direction
            }
            if (dupInBatch || isDuplicate(t)) duplicates++ else unique += t
        }

        val byMerchant = unique.groupBy { it.merchantKey }
        val categories = HashMap<String, Pair<BudgetCategory, Decision>>()
        var done = 0
        for ((key, group) in byMerchant) {
            val sample = group.firstOrNull { it.direction == Direction.DEBIT } ?: group.first()
            categories[key] = categorizer.categorize(sample, learned)
            onProgress(++done, byMerchant.size)
        }

        val history = existing.toMutableList()
        val out = unique.map { t ->
            val (cat, decision) = if (t.direction == Direction.CREDIT) {
                BudgetCategory.INCOME to Decision(BudgetCategory.INCOME.choice, 1f, mapOf(BudgetCategory.INCOME.choice to 1f), DecisionSource.RULES)
            } else categories.getValue(t.merchantKey)
            val point = TxPoint(t.merchantKey, t.merchant, t.amount, t.currency, t.timestamp, cat, t.direction)
            val anomalies = SpendingInsights.anomalies(point, history)
            history += point
            ImportedTransaction(t, cat, decision.confidence, anomalies)
        }
        return out to duplicates
    }

    companion object {
        private val NOISE = Regex(
            """(?i)\b(pos|purchase|card|debit|credit|visa|mastercard|mc|apple pay|google pay|contactless|txn|trx|ref[:#]?\s*\w+|\d{4,}|x{2,}\d+|\*+\d+)\b""",
        )

        /** "POS PURCHASE CARREFOUR CITY CENTER DOHA QA 4421" -> "Carrefour City Center Doha" */
        fun cleanDescription(raw: String): String {
            val s = raw.replace(NOISE, " ").replace(Regex("""\s+"""), " ").trim().trim('-', '*', '/', ' ')
            val words = s.split(' ').filter { it.isNotBlank() && it.lowercase() !in setOf("qa", "qat", "us", "gb", "ae") }.take(4)
            val cleaned = words.joinToString(" ").ifBlank { raw.trim() }
            return if (cleaned == cleaned.uppercase()) cleaned.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else cleaned
        }
    }
}
