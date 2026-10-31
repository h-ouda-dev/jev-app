package app.jev.core

import app.jev.core.model.Decision
import app.jev.core.model.DecisionModel
import app.jev.core.model.DecisionRequest
import app.jev.core.model.NotificationEvent
import app.jev.core.money.BudgetCategory
import app.jev.core.money.TransactionParser
import app.jev.core.scam.RedFlag
import app.jev.core.scam.ScamShield
import app.jev.core.triage.TriageBucket
import kotlin.math.abs

/** One labelled example: the request the app would really send, and the acceptable answers. */
data class DiagnosticCase(
    val feature: String,
    val name: String,
    val request: DecisionRequest,
    val accepted: Set<String>,
    val rules: (DecisionRequest) -> Decision,
)

data class CaseResult(
    val case: DiagnosticCase,
    val decision: Decision?,
    val error: String?,
    val millis: Long,
    val rulesDecision: Decision,
) {
    val passed: Boolean get() = decision != null && decision.choice in case.accepted
    val rulesPassed: Boolean get() = rulesDecision.choice in case.accepted
}

data class DiagnosticReport(val results: List<CaseResult>) {
    val passed: Int get() = results.count { it.passed }
    val total: Int get() = results.size
    val accuracy: Float get() = if (total == 0) 0f else passed.toFloat() / total
    val rulesAccuracy: Float get() = if (total == 0) 0f else results.count { it.rulesPassed }.toFloat() / total
    val errors: Int get() = results.count { it.error != null }
    val medianMillis: Long get() = results.map { it.millis }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

    /** Sanity checks every model must satisfy, independent of how smart it is. */
    val wellFormed: Boolean get() = results.all { r ->
        val d = r.decision ?: return@all false
        d.choice in r.case.request.choices &&
            d.probabilities.keys == r.case.request.choices.toSet() &&
            abs(d.probabilities.values.sum() - 1f) < 1e-3f &&
            d.probabilities.values.all { !it.isNaN() && it in 0f..1f }
    }

    fun byFeature(): Map<String, Pair<Int, Int>> =
        results.groupBy { it.case.feature }.mapValues { (_, rs) -> rs.count { it.passed } to rs.size }
}

/**
 * The self-test the app runs after installing a model (and from Settings):
 * real requests built by the same code the features use, with known answers.
 */
object Diagnostics {
    private val pipeline = JevPipeline()

    val cases: List<DiagnosticCase> by lazy { buildCases() }

    fun run(model: DecisionModel, cases: List<DiagnosticCase> = this.cases, onProgress: (Int, Int) -> Unit = { _, _ -> }): DiagnosticReport {
        val results = cases.mapIndexed { i, c ->
            onProgress(i, cases.size)
            val t0 = System.nanoTime()
            val (decision, error) = try {
                model.decide(c.request) to null
            } catch (t: Throwable) {
                null to (t.message ?: t.javaClass.simpleName)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            CaseResult(c, decision, error, ms, c.rules(c.request))
        }
        onProgress(cases.size, cases.size)
        return DiagnosticReport(results)
    }

    private fun buildCases(): List<DiagnosticCase> {
        val out = mutableListOf<DiagnosticCase>()

        fun triage(name: String, app: String, pkg: String, title: String, text: String, category: String?, vararg ok: TriageBucket, sender: String? = null) {
            val e = NotificationEvent("diag", pkg, app, title, text, category, 1_730_000_000_000, sender)
            out += DiagnosticCase("Triage", name, pipeline.triage.request(e, false), ok.map { it.choice }.toSet()) { pipeline.triage.rules(e, false) }
        }
        triage("Family emergency", "WhatsApp", "com.whatsapp", "Mom", "Call me as soon as you can, your father is in the hospital", "msg", TriageBucket.URGENT, sender = "Mom")
        triage("Meeting starting", "Calendar", "com.google.android.calendar", "Board meeting", "Starts in 5 minutes in room 4B", "event", TriageBucket.URGENT)
        triage("Login code", "Messages", "com.google.android.apps.messaging", "QNB", "Your login verification code is 482913. It expires in 5 minutes.", "msg", TriageBucket.URGENT, TriageBucket.CAN_WAIT)
        triage("Flash sale", "SHEIN", "com.shein.android", "SHEIN", "Flash sale! 70% off everything, today only. Shop now!", "promo", TriageBucket.SILENCE, TriageBucket.DIGEST)
        triage("Social like", "Instagram", "com.instagram.android", "Instagram", "sara_k and 12 others liked your photo", "social", TriageBucket.DIGEST, TriageBucket.SILENCE)
        triage("Work email", "Gmail", "com.google.android.gm", "Team lunch next Thursday", "Hi all, please reply by Monday if you can make it.", "email", TriageBucket.CAN_WAIT, TriageBucket.DIGEST)
        triage("Newsletter", "Gmail", "com.google.android.gm", "Your weekly newsletter", "This week in tech: 10 stories you might have missed", "email", TriageBucket.DIGEST, TriageBucket.SILENCE)

        fun scam(name: String, text: String, flag: RedFlag, yes: Boolean) {
            val req = pipeline.shield.request(text, flag)
            val evidenceRules = { r: DecisionRequest ->
                pipeline.shield.check(text).checks.first { it.flag == flag }.let { c ->
                    Decision.fromLogits(r.choices, floatArrayOf(c.probability, 1 - c.probability), app.jev.core.model.DecisionSource.RULES, 0.25f)
                }
            }
            out += DiagnosticCase("Scam shield", name, req, setOf(if (yes) ScamShield.YES else ScamShield.NO), evidenceRules)
        }
        val parcel = "DHL: Your parcel is on hold. Pay the customs fee of QAR 9.50 within 24 hours or it will be returned: dhl-parcel-fee.xyz/pay"
        val hiMum = "Hi mum, I lost my phone and this is my new number. Please send me the 6-digit code you just received, it's urgent"
        val prize = "Congratulations! You have won an iPhone 16 in our lucky draw. Claim your prize now before it expires"
        val bank = "Your account has been suspended due to unusual activity. Verify your details now to avoid permanent closure"
        val dinner = "Are we still on for dinner at 8? I booked the table at the Italian place"
        scam("Parcel fee: asks to pay", parcel, RedFlag.PAYMENT, true)
        scam("Parcel fee: urgency", parcel, RedFlag.URGENCY, true)
        scam("Parcel fee: impersonation", parcel, RedFlag.IMPERSONATION, true)
        scam("Hi mum: asks for code", hiMum, RedFlag.CODE_OR_PASSWORD, true)
        scam("Fake prize: too good", prize, RedFlag.TOO_GOOD, true)
        scam("Account suspended: threat", bank, RedFlag.THREAT, true)
        scam("Dinner: no payment", dinner, RedFlag.PAYMENT, false)
        scam("Dinner: no urgency", dinner, RedFlag.URGENCY, false)
        scam("Dinner: no code request", dinner, RedFlag.CODE_OR_PASSWORD, false)
        scam("Dinner: no threat", dinner, RedFlag.THREAT, false)

        fun money(name: String, text: String, vararg ok: BudgetCategory) {
            val tx = TransactionParser.parse(text, "com.qnb.mobile", 0) ?: error("Diagnostic transaction did not parse: $text")
            val req = pipeline.categorizer.request(tx)
            out += DiagnosticCase("Money", name, req, ok.map { it.choice }.toSet()) { pipeline.categorizer.rules(tx, it.choices) }
        }
        money("Coffee", "Purchase of QAR 24.00 at STARBUCKS VILLAGGIO with card ending 1234", BudgetCategory.DINING)
        money("Supermarket", "Purchase of QAR 312.75 at CARREFOUR HYPERMARKET with card ending 1234", BudgetCategory.GROCERIES)
        money("Streaming", "Purchase of QAR 45.00 at NETFLIX.COM with card ending 1234", BudgetCategory.SUBSCRIPTIONS, BudgetCategory.ENTERTAINMENT)
        money("Ride", "Purchase of QAR 38.50 at UBER TRIP with card ending 1234", BudgetCategory.TRANSPORT)
        money("Pharmacy", "Purchase of QAR 66.00 at WELLCARE PHARMACY with card ending 1234", BudgetCategory.HEALTH)
        money("Flight", "Purchase of QAR 2,450.00 at QATAR AIRWAYS with card ending 1234", BudgetCategory.TRAVEL)
        return out
    }
}
