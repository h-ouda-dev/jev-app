package app.heron.core

import app.heron.core.model.Decision
import app.heron.core.model.DecisionRouter
import app.heron.core.model.NotificationEvent
import app.heron.core.money.BudgetCategory
import app.heron.core.money.Categorizer
import app.heron.core.money.ParsedTransaction
import app.heron.core.money.TransactionParser
import app.heron.core.scam.LinkInspector
import app.heron.core.scam.ScamReport
import app.heron.core.scam.ScamShield
import app.heron.core.triage.TriageEngine
import app.heron.core.triage.TriagePreferences
import app.heron.core.triage.TriageResult

data class ProcessedNotification(
    val event: NotificationEvent,
    val triage: TriageResult,
    val scam: ScamReport?,
    val transaction: ParsedTransaction?,
    val category: BudgetCategory?,
    val categoryDecision: Decision?,
    val elapsedMs: Long,
)

/**
 * The single entry point the notification listener calls: one notification in,
 * triage + scam check + money extraction out. All three features share the
 * same [DecisionRouter], i.e. the same on-device model.
 */
class HeronPipeline(val router: DecisionRouter = DecisionRouter()) {
    val triage = TriageEngine(router)
    val shield = ScamShield(router)
    val categorizer = Categorizer(router)

    fun process(
        event: NotificationEvent,
        prefs: TriagePreferences,
        learnedCategories: Map<String, BudgetCategory> = emptyMap(),
        isKnownContact: Boolean = false,
    ): ProcessedNotification {
        val start = System.nanoTime()
        val tx = TransactionParser.parse(event)
        val (category, catDecision) = tx?.let { categorizer.categorize(it, learnedCategories) } ?: (null to null)
        val scam = if (shouldScamCheck(event, tx)) shield.check(event.fullText, event.packageName, isKnownContact) else null
        val tri = triage.triage(event, prefs)
        return ProcessedNotification(event, tri, scam, tx, category, catDecision, (System.nanoTime() - start) / 1_000_000)
    }

    /** Scam-check person-to-person messages, mail, and anything with a link; skip our own bank's confirmed transactions. */
    fun shouldScamCheck(event: NotificationEvent, tx: ParsedTransaction?): Boolean {
        if (event.text.isBlank()) return false
        val hasLink = LinkInspector.extract(event.fullText).isNotEmpty()
        val conversational = event.category in setOf("msg", "email", "social") || event.packageName in TriageEngine.MESSAGING_APPS
        return hasLink || conversational || (tx == null && event.text.length > 40)
    }
}
