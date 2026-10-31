package app.heron.data

import app.heron.HeronApp
import app.heron.core.model.NotificationEvent
import app.heron.core.money.BudgetCategory
import app.heron.core.money.Direction
import app.heron.core.money.ParsedTransaction
import app.heron.core.money.SpendingInsights
import app.heron.core.money.TxPoint
import app.heron.core.money.normalizeMerchant
import app.heron.core.triage.TriagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Realistic sample content, run through the real pipeline so every label,
 * verdict and category is what Heron would actually decide. Used for
 * screenshots, store listing images and demos (debug builds only in the UI).
 */
object DemoData {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private data class N(val minsAgo: Long, val app: String, val pkg: String, val title: String, val text: String, val category: String?, val sender: String? = null)

    private val notifications = listOf(
        N(4, "WhatsApp", "com.whatsapp", "Mom", "Call me when you land, love you ❤️", "msg", "Mom"),
        N(11, "Messages", "com.google.android.apps.messaging", "+974 3312 0098", "QNB: Your card has been suspended due to unusual activity. Verify within 24 hours at qnb-secure-verify.com", "msg", "+974 3312 0098"),
        N(26, "Calendar", "com.google.android.calendar", "Dentist", "Starts in 30 minutes · Al Sadd Dental Clinic", "event"),
        N(38, "Slack", "com.slack", "#launch", "Omar: Build 2.3 is green, ready for QA whenever you are", "msg", "#launch"),
        N(52, "SHEIN", "com.shein.android", "SHEIN", "Flash sale! 70% off everything, today only. Shop now!", "promo"),
        N(75, "Instagram", "com.instagram.android", "Instagram", "nour.k and 8 others liked your photo", "social"),
        N(96, "QNB", "com.qnb.mobile", "Card transaction", "QAR 245.50 spent at CARREFOUR CITY CENTER with card ending 4421", null),
        N(120, "Gmail", "com.google.android.gm", "Sara · Design", "Updated mockups for Monday's review are in the shared folder", "email", "Sara · Design"),
        N(150, "talabat", "com.talabat", "talabat", "Hungry? Get 40% off your next order, today only 🍔", "promo"),
        N(185, "Messages", "com.google.android.apps.messaging", "DHL Express", "Your parcel is on hold. Pay the customs fee of QAR 9.50 within 24 hours: dhl-parcel-fee.xyz/pay", "msg", "DHL Express"),
        N(230, "LinkedIn", "com.linkedin.android", "LinkedIn", "You appeared in 12 searches this week", "social"),
        N(260, "Messages", "com.google.android.apps.messaging", "Ahmed", "Running 10 min late, sorry! Order me a flat white?", "msg", "Ahmed"),
        N(310, "YouTube", "com.google.android.youtube", "YouTube", "New from Marques Brownlee: The truth about foldables", "recommendation"),
        N(360, "Gmail", "com.google.android.gm", "The Morning Brew", "Your weekly newsletter: 5 stories you might have missed", "email"),
        N(420, "noon", "com.noon.buyerapp", "noon", "Your order has shipped and arrives tomorrow", null),
        N(26 * 60, "WhatsApp", "com.whatsapp", "Family", "Dad: Dinner at ours on Friday? 🍲", "msg", "Family"),
        N(27 * 60, "Netflix", "com.netflix.mediaclient", "Netflix", "New arrivals this week: 6 shows picked for you", "promo"),
        N(29 * 60, "Messages", "com.google.android.apps.messaging", "+1 (877) 555-0142", "Congratulations! You have won an iPhone 16 in our lucky draw. Claim your prize now: bit.ly/3xWin", "msg", "+1 (877) 555-0142"),
        N(30 * 60, "Uber", "com.ubercab", "Uber", "Your driver Khalid is arriving in a white Camry", null),
    )

    private data class T(val daysAgo: Int, val merchant: String, val amount: Double, val dir: Direction = Direction.DEBIT, val hour: Int = 13)

    private fun transactions(): List<T> {
        val out = mutableListOf<T>()
        for (m in 0..2) {
            val base = m * 30
            out += T(base + 3, "NETFLIX.COM", 45.0)
            out += T(base + 9, "SPOTIFY", if (m == 0) 23.99 else 19.99)
            out += T(base + 12, "APPLE.COM/BILL ICLOUD", 3.99)
            out += T(base + 1, "FITNESS FIRST", 300.0)
            out += T(base + 25, "OOREDOO POSTPAID", 250.0)
            out += T(base + 27, "SALARY ACME TRADING", 18_000.0, Direction.CREDIT, hour = 9)
        }
        val groceries = listOf("CARREFOUR CITY CENTER", "LULU HYPERMARKET", "AL MEERA")
        for (d in 0 until 70 step 4) out += T(d + 1, groceries[(d / 4) % 3], 120.0 + (d * 37 % 260))
        for (d in 0 until 70 step 2) out += T(d, if (d % 4 == 0) "STARBUCKS VILLAGGIO" else "COSTA COFFEE", 24.0 + (d % 5) * 3.5, hour = 8)
        for (d in 1 until 70 step 3) out += T(d, "TALABAT", 58.0 + (d * 13 % 55), hour = 20)
        for (d in 0 until 70 step 3) out += T(d, "KARWA TAXI", 26.0 + (d * 7 % 30), hour = 18)
        out += T(2, "KARWA TAXI", 186.0, hour = 23) // the unusual one
        out += T(18, "QATAR AIRWAYS", 2450.0)
        out += T(6, "WELLCARE PHARMACY", 66.0)
        out += T(11, "VIRGIN MEGASTORE", 189.0)
        out += T(15, "ZARA", 340.0)
        out += T(8, "VOX CINEMAS", 95.0)
        return out
    }

    private val DISPLAY = mapOf(
        "NETFLIX.COM" to "Netflix", "SPOTIFY" to "Spotify", "APPLE.COM/BILL ICLOUD" to "iCloud+",
        "FITNESS FIRST" to "Fitness First", "OOREDOO POSTPAID" to "Ooredoo", "SALARY ACME TRADING" to "Salary · Acme Trading",
        "CARREFOUR CITY CENTER" to "Carrefour", "LULU HYPERMARKET" to "LuLu Hypermarket", "AL MEERA" to "Al Meera",
        "STARBUCKS VILLAGGIO" to "Starbucks", "COSTA COFFEE" to "Costa Coffee", "TALABAT" to "talabat",
        "KARWA TAXI" to "Karwa Taxi", "QATAR AIRWAYS" to "Qatar Airways", "WELLCARE PHARMACY" to "Wellcare Pharmacy",
        "VIRGIN MEGASTORE" to "Virgin Megastore", "ZARA" to "Zara", "VOX CINEMAS" to "VOX Cinemas",
    )

    suspend fun seed(app: HeronApp) = withContext(Dispatchers.Default) {
        val db = app.db
        db.notifications().clear()
        db.transactions().clear()
        if (app.settings.value.vipNames.isEmpty()) app.settings.update { it.copy(vipNames = setOf("Mom")) }
        if (app.settings.value.monthlyBudget <= 0) app.settings.update { it.copy(monthlyBudget = 6000.0, currency = "QAR") }
        val now = System.currentTimeMillis()
        val prefs = TriagePreferences(vipNames = app.settings.value.vipNames)

        notifications.forEachIndexed { i, n ->
            val e = NotificationEvent("demo-$i", n.pkg, n.app, n.title, n.text, n.category, now - n.minsAgo * MIN, n.sender)
            val r = app.pipeline.process(e, prefs)
            db.notifications().insert(
                NotificationRecord(
                    sbnKey = e.key, packageName = e.packageName, appLabel = e.appLabel, title = e.title, text = e.text,
                    sender = e.sender, senderKey = e.senderKey, postedAt = e.postedAt,
                    bucket = r.triage.bucket.name, confidence = r.triage.decision.confidence,
                    probabilities = app.heron.core.triage.TriageBucket.entries.joinToString(";") { "${it.name}=${r.triage.decision.probabilityOf(it.choice)}" },
                    reason = r.triage.reason, decidedBy = r.triage.decision.source.name,
                    inDigest = r.triage.bucket == app.heron.core.triage.TriageBucket.DIGEST,
                    scamVerdict = r.scam?.verdict?.name, scamRisk = r.scam?.riskScore,
                    scamFlags = r.scam?.raisedFlags?.joinToString(",") { it.flag.name },
                    latencyMs = r.elapsedMs,
                ),
            )
        }

        val history = mutableListOf<TxPoint>()
        transactions().sortedByDescending { it.daysAgo }.forEach { t ->
            val ts = minOf(now - t.daysAgo * DAY - (now % DAY) + t.hour * HOUR, now - (t.daysAgo * 3 + 40) * MIN)
            val raw = if (t.dir == Direction.DEBIT) "QAR %.2f spent at %s with card ending 4421".format(t.amount, t.merchant)
            else "QAR %.2f credited to your account: %s".format(t.amount, t.merchant)
            val tx = ParsedTransaction(t.amount, "QAR", t.merchant, t.dir, "4421", "com.qnb.mobile", raw, ts)
            val cat = if (t.dir == Direction.CREDIT) BudgetCategory.INCOME else app.pipeline.categorizer.categorize(tx).first
            val point = TxPoint(normalizeMerchant(t.merchant), t.merchant, t.amount, "QAR", ts, cat, t.dir)
            val anomalies = SpendingInsights.anomalies(point, history)
            history += point
            db.transactions().insert(
                TransactionRecord(
                    amount = t.amount, currency = "QAR", merchant = DISPLAY[t.merchant] ?: t.merchant,
                    merchantKey = point.merchantKey, direction = t.dir.name, category = cat.name, confidence = 1f,
                    cardHint = "4421", sourcePackage = "com.qnb.mobile", rawText = raw, timestamp = ts,
                    anomalies = anomalies.joinToString("\n") { it.message },
                ),
            )
        }
    }
}
