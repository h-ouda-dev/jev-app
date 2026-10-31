package app.jev.core.money

import app.jev.core.model.NotificationEvent
import app.jev.core.text.containsAnyPhrase
import app.jev.core.text.norm

/**
 * Pulls amount / currency / merchant / direction out of bank and card
 * notifications. Deliberately conservative: promos and OTP messages are
 * rejected so marketing "QAR 50 off" never lands in the budget.
 */
object TransactionParser {
    private const val CUR = """QAR|QR|USD|EUR|GBP|AED|SAR|KWD|BHD|OMR|EGP|INR|PKR|JOD|TRY|CAD|AUD|CHF|JPY|CNY"""
    private const val SYM = """\$|€|£|₹|ر\.ق"""
    private const val NUM = """\d{1,3}(?:,\d{3})+(?:\.\d{1,3})?|\d+(?:\.\d{1,3})?"""
    private val AMOUNT_BEFORE = Regex("""(?i)(?<![A-Z])($CUR|$SYM)\s?($NUM)""")
    private val AMOUNT_AFTER = Regex("""(?i)($NUM)\s?($CUR)(?![A-Z])""")
    private val CARD = Regex("""(?i)(?:card|a/c|acct|account)[^0-9]{0,20}(?:ending(?: in| with)?|no\.?|x+|\*+|#)?\s*[x*]*(\d{3,4})\b""")
    private val MERCHANT = Regex(
        """(?i)\b(?:at|to|from|@|merchant[:\s]|towards|for payment to)\s+([A-Za-z0-9&'.*\- ]{2,40}?)(?=\s+(?:on|using|with|via|ref|avl|avail|available|bal|balance|card|at\s+\d|for)\b|[.,;:\n]|\s*$)"""
    )

    private val DEBIT_WORDS = listOf(
        "spent", "debited", "purchase", "paid", "payment of", "used for", "used at", "withdrawn", "withdrawal",
        "charged", "deducted", "pos", "transaction of", "sent", "transfer to",
    )
    private val CREDIT_WORDS = listOf(
        "credited", "received", "deposit", "deposited", "refund", "refunded", "salary", "reversal", "cashback credited",
    )
    private val REJECT_WORDS = listOf(
        "otp", "one time password", "one-time password", "verification code", "do not share", "offer", "discount",
        "% off", "win", "get up to", "earn up to", "promo", "limited time", "apply now", "pre-approved", "due date", "minimum due",
    )

    val FINANCE_APP_HINTS = listOf("bank", "qnb", "cbq", "pay", "wallet", "card", "finance", "money", "revolut", "wise", "hsbc", "citi", "chase")

    fun parse(event: NotificationEvent): ParsedTransaction? = parse(event.fullText, event.packageName, event.postedAt)

    fun parse(text: String, sourcePackage: String, timestamp: Long): ParsedTransaction? {
        val t = text.norm()
        if (t.containsAnyPhrase(REJECT_WORDS)) return null
        val isDebit = t.containsAnyPhrase(DEBIT_WORDS)
        val isCredit = t.containsAnyPhrase(CREDIT_WORDS)
        val financeApp = FINANCE_APP_HINTS.any { sourcePackage.lowercase().contains(it) }
        if (!isDebit && !isCredit && !financeApp) return null

        val (amount, currency) = amount(text) ?: return null
        if (amount <= 0.0) return null
        val direction = when {
            isCredit && !isDebit -> Direction.CREDIT
            isCredit && isDebit -> if (t.indexOfFirstOf(CREDIT_WORDS) < t.indexOfFirstOf(DEBIT_WORDS)) Direction.CREDIT else Direction.DEBIT
            else -> Direction.DEBIT
        }
        val merchant = merchant(text) ?: if (direction == Direction.CREDIT) "Incoming" else "Unknown merchant"
        val card = CARD.find(text)?.groupValues?.get(1)
        return ParsedTransaction(amount, currency, merchant, direction, card, sourcePackage, text, timestamp)
    }

    private fun amount(text: String): Pair<Double, String>? {
        val before = AMOUNT_BEFORE.find(text)
        val after = AMOUNT_AFTER.find(text)
        val (num, cur) = when {
            before != null && (after == null || before.range.first <= after.range.first) -> before.groupValues[2] to before.groupValues[1]
            after != null -> after.groupValues[1] to after.groupValues[2]
            else -> return null
        }
        val currency = when (cur.uppercase()) {
            "$" -> "USD"; "€" -> "EUR"; "£" -> "GBP"; "₹" -> "INR"; "QR", "ر.ق" -> "QAR"
            else -> cur.uppercase()
        }
        return num.replace(",", "").toDoubleOrNull()?.let { it to currency }
    }

    private fun merchant(text: String): String? = MERCHANT.findAll(text)
        .map { it.groupValues[1].trim().trimEnd('.', '-') }
        .firstOrNull { cand ->
            cand.length >= 2 && cand.any { it.isLetter() } &&
                !AMOUNT_BEFORE.matches(cand) && cand.lowercase() !in setOf("your", "you", "the", "card", "account")
        }

    private fun String.indexOfFirstOf(words: List<String>) = words.mapNotNull { w -> indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
}
