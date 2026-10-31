package app.jev.core.money

import java.util.Locale

enum class Direction { DEBIT, CREDIT }

enum class BudgetCategory(val label: String, val choice: String) {
    GROCERIES("Groceries", "groceries and supermarkets"),
    DINING("Dining", "restaurants, cafes and food delivery"),
    TRANSPORT("Transport", "transport, taxis, fuel and parking"),
    SHOPPING("Shopping", "shopping, clothes and electronics"),
    BILLS("Bills", "bills, utilities, phone and internet"),
    SUBSCRIPTIONS("Subscriptions", "subscriptions and streaming services"),
    ENTERTAINMENT("Entertainment", "entertainment, cinema and games"),
    HEALTH("Health", "health, pharmacy and fitness"),
    TRAVEL("Travel", "travel, flights and hotels"),
    TRANSFERS("Transfers", "transfers, cash withdrawals and payments to people"),
    INCOME("Income", "income, salary and refunds"),
    OTHER("Other", "something else");

    companion object {
        fun fromChoice(choice: String) = entries.first { it.choice == choice }
    }
}

/** A transaction extracted from a bank / card / wallet notification. */
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val direction: Direction,
    val cardHint: String?,
    val sourcePackage: String,
    val rawText: String,
    val timestamp: Long,
) {
    val merchantKey: String get() = normalizeMerchant(merchant)
}

/** Canonical merchant key so "NETFLIX.COM 866-579" and "Netflix" group together. */
fun normalizeMerchant(m: String): String {
    var s = m.lowercase(Locale.ROOT)
    s = s.substringBefore('*').ifBlank { m.lowercase(Locale.ROOT) }
    s = s.replace(Regex("""\.(com|net|org|qa|co|io)\b"""), " ")
    s = s.replace(Regex("""[^\p{L}& ]"""), " ")
    val words = s.split(' ').filter { it.length > 1 && it !in MERCHANT_NOISE }
    return words.take(3).joinToString(" ").ifBlank { m.lowercase(Locale.ROOT).trim() }
}

private val MERCHANT_NOISE = setOf("pos", "purchase", "the", "ltd", "llc", "inc", "wll", "co", "doha", "qa", "qat", "www")
