package app.jev.core

import app.jev.core.model.NotificationEvent
import app.jev.core.money.BudgetCategory
import app.jev.core.money.Direction
import app.jev.core.money.SpendingInsights
import app.jev.core.money.TransactionParser
import app.jev.core.money.TxPoint
import app.jev.core.money.Period
import app.jev.core.money.Anomaly
import app.jev.core.scam.LinkInspector
import app.jev.core.scam.RedFlag
import app.jev.core.scam.ScamVerdict
import app.jev.core.triage.TriageBucket
import app.jev.core.triage.TriagePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTest {
    private val pipeline = JevPipeline()
    private fun ev(text: String, title: String = "Someone", pkg: String = "com.google.android.apps.messaging", category: String? = "msg", sender: String? = title) =
        NotificationEvent("k", pkg, "Messages", title, text, category, 1_700_000_000_000, sender)

    // ---- Scam shield ----
    @Test fun parcelScamIsLikelyScam() {
        val r = pipeline.shield.check("DHL: Your parcel is on hold. Pay the customs fee of QAR 9.50 within 24 hours or it will be returned: https://dhl-parcel-fee.xyz/pay")
        assertEquals(ScamVerdict.LIKELY_SCAM, r.verdict)
        val raised = r.raisedFlags.map { it.flag }.toSet()
        assertTrue(raised.containsAll(listOf(RedFlag.IMPERSONATION, RedFlag.PAYMENT, RedFlag.URGENCY, RedFlag.SUSPICIOUS_LINK)))
    }

    @Test fun codeRequestIsFlagged() {
        val r = pipeline.shield.check("Hi mum, I lost my phone. Please send me the code you just received, it's urgent")
        assertTrue(r.raisedFlags.any { it.flag == RedFlag.CODE_OR_PASSWORD })
        assertTrue(r.verdict != ScamVerdict.SAFE)
    }

    @Test fun normalMessageIsSafe() {
        assertEquals(ScamVerdict.SAFE, pipeline.shield.check("Are we still on for dinner at 8? I booked the table").verdict)
        assertEquals(ScamVerdict.SAFE, pipeline.shield.check("Your verification code is 482913. Do not share it with anyone.").verdict)
    }

    @Test fun linkInspector() {
        assertTrue(LinkInspector.inspectUrl("bit.ly/3xYz").suspicious)
        assertTrue(LinkInspector.inspectUrl("https://paypal-secure-login.com/verify").suspicious)
        assertFalse(LinkInspector.inspectUrl("https://www.paypal.com/myaccount").suspicious)
        assertFalse(LinkInspector.inspectUrl("https://www.qnb.com/sites/qnb/qnbqatar").suspicious)
        assertEquals("qatarpost.qa", LinkInspector.registrableDomain("track.qatarpost.qa"))
        assertEquals("dohabank.com.qa", LinkInspector.registrableDomain("www.dohabank.com.qa"))
    }

    // ---- Triage ----
    @Test fun vipIsUrgentAndNeverSilenced() {
        val r = pipeline.triage.triage(ev("call me when you can", title = "Mom"), TriagePreferences(vipNames = setOf("mom")))
        assertEquals(TriageBucket.URGENT, r.bucket)
    }

    @Test fun promoIsSilencedOrDigested() {
        val r = pipeline.triage.triage(
            ev("Flash sale! 50% off everything, limited time", title = "SHEIN", pkg = "com.shein.android", category = "promo", sender = null),
            TriagePreferences(),
        )
        assertTrue(r.bucket == TriageBucket.SILENCE || r.bucket == TriageBucket.DIGEST)
    }

    @Test fun learnedSenderRuleWins() {
        val e = ev("lol", title = "Group chat")
        val r = pipeline.triage.triage(e, TriagePreferences(senderRules = mapOf(e.senderKey to TriageBucket.SILENCE)))
        assertEquals(TriageBucket.SILENCE, r.bucket)
        assertEquals(1f, r.decision.confidence)
    }

    // ---- Money ----
    @Test fun parsesCommonBankFormats() {
        val a = TransactionParser.parse("Your QNB card ending 1234 was used for QAR 245.50 at CARREFOUR CITY CENTER on 31/10/2026", "com.qnb.mobile", 0)
        assertNotNull(a)
        assertEquals(245.50, a!!.amount, 1e-9)
        assertEquals("QAR", a.currency)
        assertEquals("CARREFOUR CITY CENTER", a.merchant)
        assertEquals("1234", a.cardHint)
        assertEquals(Direction.DEBIT, a.direction)

        val b = TransactionParser.parse("You spent \$15.99 at NETFLIX.COM with card ****9876", "com.chase.sig.android", 0)!!
        assertEquals(15.99, b.amount, 1e-9)
        assertEquals("USD", b.currency)
        assertEquals("netflix", b.merchantKey)

        val c = TransactionParser.parse("QAR 12,500.00 has been credited to your account as SALARY", "com.cbq.mobile", 0)!!
        assertEquals(12500.0, c.amount, 1e-9)
        assertEquals(Direction.CREDIT, c.direction)
    }

    @Test fun rejectsPromosAndOtps() {
        assertNull(TransactionParser.parse("Get 20% off up to QAR 50 on your next order!", "com.talabat", 0))
        assertNull(TransactionParser.parse("OTP for your purchase of QAR 300 at NOON is 448812. Do not share.", "com.qnb.mobile", 0))
        assertNull(TransactionParser.parse("See you at 5", "com.whatsapp", 0))
    }

    @Test fun categorizesByMerchant() {
        val tx = TransactionParser.parse("Purchase of QAR 38.00 at STARBUCKS VILLAGGIO on 01/11", "com.qnb.mobile", 0)!!
        assertEquals(BudgetCategory.DINING, pipeline.categorizer.categorize(tx).first)
        val learned = mapOf(tx.merchantKey to BudgetCategory.ENTERTAINMENT)
        assertEquals(BudgetCategory.ENTERTAINMENT, pipeline.categorizer.categorize(tx, learned).first)
    }

    private val day = 86_400_000L
    private fun pt(m: String, amt: Double, d: Int, cat: BudgetCategory = BudgetCategory.OTHER) =
        TxPoint(m.lowercase(), m, amt, "QAR", d * day, cat, Direction.DEBIT)

    @Test fun detectsMonthlySubscriptionAndPriceIncrease() {
        val hist = listOf(pt("Spotify", 19.99, 0), pt("Spotify", 19.99, 30), pt("Spotify", 19.99, 61), pt("Lulu", 80.0, 5), pt("Lulu", 230.0, 9))
        val subs = SpendingInsights.detectSubscriptions(hist)
        assertEquals(1, subs.size)
        assertEquals(Period.MONTHLY, subs[0].period)
        val next = pt("Spotify", 23.99, 91)
        val anomalies = SpendingInsights.anomalies(next, hist)
        assertTrue(anomalies.any { it.kind == Anomaly.Kind.PRICE_INCREASE })
    }

    @Test fun flagsUnusualAmountAndDuplicates() {
        val hist = (1..5).map { pt("Karwa", 25.0 + it, it) }
        assertTrue(SpendingInsights.anomalies(pt("Karwa", 180.0, 10), hist).any { it.kind == Anomaly.Kind.UNUSUAL_AMOUNT })
        val dup = TxPoint("karwa", "Karwa", 26.0, "QAR", 1 * day + 60_000, BudgetCategory.TRANSPORT, Direction.DEBIT)
        assertTrue(SpendingInsights.anomalies(dup, hist).any { it.kind == Anomaly.Kind.POSSIBLE_DUPLICATE })
    }

    // ---- Pipeline ----
    @Test fun pipelineHandlesBankNotificationEndToEnd() {
        val e = NotificationEvent("k", "com.qnb.mobile", "QNB", "Card transaction", "QAR 61.25 spent at UBER TRIP on card ending 4421", null, 0)
        val p = pipeline.process(e, TriagePreferences())
        assertNotNull(p.transaction)
        assertEquals(BudgetCategory.TRANSPORT, p.category)
        assertNull(p.scam)
    }
}
