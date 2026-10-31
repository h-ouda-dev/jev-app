package app.heron.core.money

/**
 * Offline currency conversion. Heron never calls a rates service (nothing
 * leaves the phone), so it ships a table:
 *  - GCC currencies and JOD are pegged to the US dollar, so those
 *    conversions are exact (e.g. 1 USD = 3.64 QAR, always).
 *  - Floating currencies use reference rates from [RATES_AS_OF] and are
 *    shown as approximate in the app.
 */
object Fx {
    const val RATES_AS_OF = "2025"

    /** Units of each currency per 1 US dollar. */
    private val PER_USD = mapOf(
        "USD" to 1.0,
        "QAR" to 3.64, "AED" to 3.6725, "SAR" to 3.75, "BHD" to 0.376, "OMR" to 0.3845, "JOD" to 0.709,
        "KWD" to 0.307, "EUR" to 0.86, "GBP" to 0.75, "CHF" to 0.80, "CAD" to 1.39, "AUD" to 1.53, "JPY" to 152.0,
        "CNY" to 7.12, "INR" to 88.0, "PKR" to 281.0, "EGP" to 48.5, "TRY" to 42.0, "LKR" to 300.0, "PHP" to 58.0,
        "BDT" to 122.0, "NPR" to 140.0, "SGD" to 1.30, "HKD" to 7.78, "MYR" to 4.25, "THB" to 33.0, "ZAR" to 17.5,
    )

    /** Fixed-rate currencies: conversions between any two of these are exact. */
    private val PEGGED = setOf("USD", "QAR", "AED", "SAR", "BHD", "OMR", "JOD")

    fun supports(currency: String) = currency.uppercase() in PER_USD

    /** Converts [amount] from [from] into [to], or null if either currency is unknown. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val f = from.uppercase()
        val t = to.uppercase()
        if (f == t) return amount
        val pf = PER_USD[f] ?: return null
        val pt = PER_USD[t] ?: return null
        return amount / pf * pt
    }

    /** True when the conversion uses a fixed peg (exact) rather than a reference rate. */
    fun isExact(from: String, to: String): Boolean {
        val f = from.uppercase()
        val t = to.uppercase()
        return f == t || (f in PEGGED && t in PEGGED)
    }
}
