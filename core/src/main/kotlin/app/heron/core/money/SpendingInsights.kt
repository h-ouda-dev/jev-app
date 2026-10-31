package app.heron.core.money

import kotlin.math.abs

/** A charge that looks unusual compared with the history. */
data class Anomaly(val kind: Kind, val message: String) {
    enum class Kind { UNUSUAL_AMOUNT, NEW_MERCHANT_LARGE, POSSIBLE_DUPLICATE, PRICE_INCREASE }
}

enum class Period(val label: String, val days: Double) { WEEKLY("weekly", 7.0), MONTHLY("monthly", 30.4), YEARLY("yearly", 365.0) }

data class Subscription(
    val merchantKey: String,
    val displayName: String,
    val amount: Double,
    val currency: String,
    val period: Period,
    val lastCharged: Long,
    val nextExpected: Long,
    val chargeCount: Int,
    val priceIncreased: Boolean,
) {
    /** Annualised cost, the number that makes people cancel forgotten subscriptions. */
    val yearlyCost: Double get() = amount * (365.0 / period.days)
}

/** Minimal shape SpendingInsights needs, so the app can pass DB rows directly. */
data class TxPoint(val merchantKey: String, val merchant: String, val amount: Double, val currency: String, val timestamp: Long, val category: BudgetCategory, val direction: Direction)

object SpendingInsights {
    private const val DAY = 86_400_000L

    /** Checks [tx] against previous debits in [history] (which must not include [tx]). */
    fun anomalies(tx: TxPoint, history: List<TxPoint>): List<Anomaly> {
        if (tx.direction != Direction.DEBIT) return emptyList()
        val debits = history.filter { it.direction == Direction.DEBIT && it.currency == tx.currency }
        val out = mutableListOf<Anomaly>()
        val sameMerchant = debits.filter { it.merchantKey == tx.merchantKey }

        sameMerchant.firstOrNull { abs(it.amount - tx.amount) < 0.01 && abs(it.timestamp - tx.timestamp) < 10 * 60_000L }?.let {
            out += Anomaly(Anomaly.Kind.POSSIBLE_DUPLICATE, "Charged the same amount at ${tx.merchant} twice within 10 minutes")
        }
        if (sameMerchant.size >= 3) {
            val med = median(sameMerchant.map { it.amount })
            if (tx.amount > med * 2.5 && tx.amount - med > 20) {
                out += Anomaly(Anomaly.Kind.UNUSUAL_AMOUNT, "%.2f %s at %s is %.1f× your usual %.2f".format(tx.amount, tx.currency, tx.merchant, tx.amount / med, med))
            }
        } else if (sameMerchant.isEmpty() && debits.size >= 10) {
            val p90 = percentile(debits.map { it.amount }, 0.9)
            if (tx.amount > p90 * 1.5) {
                out += Anomaly(Anomaly.Kind.NEW_MERCHANT_LARGE, "First time at ${tx.merchant}, and larger than 90%% of your payments".replace("%%", "%"))
            }
        }
        val sub = detectSubscriptions(history + tx).firstOrNull { it.merchantKey == tx.merchantKey }
        if (sub?.priceIncreased == true && sub.lastCharged == tx.timestamp) {
            out += Anomaly(Anomaly.Kind.PRICE_INCREASE, "${sub.displayName} subscription went up to %.2f %s".format(tx.amount, tx.currency))
        }
        return out
    }

    /** Finds charges that repeat at a regular interval with a similar amount. */
    fun detectSubscriptions(history: List<TxPoint>): List<Subscription> =
        history.filter { it.direction == Direction.DEBIT }
            .groupBy { it.merchantKey to it.currency }
            .mapNotNull { (_, txs) -> subscriptionOf(txs.sortedBy { it.timestamp }) }
            .sortedByDescending { it.yearlyCost }

    private fun subscriptionOf(txs: List<TxPoint>): Subscription? {
        val knownSub = txs.any { it.category == BudgetCategory.SUBSCRIPTIONS }
        if (txs.size < 2) return null
        // Keep the longest tail of charges with similar amounts (allow one price change).
        val gaps = txs.zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() / DAY }.filter { it > 2 }
        if (gaps.isEmpty()) return null
        val medGap = median(gaps)
        val period = Period.entries.firstOrNull { p -> abs(medGap - p.days) <= p.days * 0.15 + 1 } ?: return null
        val regular = gaps.count { abs(it - period.days) <= period.days * 0.2 + 2 }
        if (regular < gaps.size * 0.7) return null
        val last = txs.last()
        val prev = txs[txs.size - 2]
        val amountsSimilar = txs.map { it.amount }.let { a -> a.max() <= a.min() * 1.35 + 1 }
        if (!amountsSimilar && !knownSub) return null
        if (txs.size < 3 && !knownSub && period != Period.MONTHLY) return null
        return Subscription(
            merchantKey = last.merchantKey,
            displayName = last.merchant,
            amount = last.amount,
            currency = last.currency,
            period = period,
            lastCharged = last.timestamp,
            nextExpected = last.timestamp + (period.days * DAY).toLong(),
            chargeCount = txs.size,
            priceIncreased = last.amount > prev.amount * 1.03 + 0.01,
        )
    }

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun percentile(xs: List<Double>, p: Double): Double {
        val s = xs.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }
}
