package app.heron.core.money

import app.heron.core.model.Decision
import app.heron.core.model.DecisionRequest
import app.heron.core.model.DecisionRouter
import app.heron.core.model.DecisionSource
import app.heron.core.model.ScoredRules
import app.heron.core.text.compact

class Categorizer(private val router: DecisionRouter) {

    /** [learned] maps merchantKey -> category the user picked before. */
    fun categorize(tx: ParsedTransaction, learned: Map<String, BudgetCategory> = emptyMap()): Pair<BudgetCategory, Decision> {
        learned[tx.merchantKey]?.let { cat ->
            return cat to Decision(cat.choice, 1f, mapOf(cat.choice to 1f), DecisionSource.RULES)
        }
        if (tx.direction == Direction.CREDIT) {
            return BudgetCategory.INCOME to Decision(BudgetCategory.INCOME.choice, 1f, mapOf(BudgetCategory.INCOME.choice to 1f), DecisionSource.RULES)
        }
        val request = request(tx)
        val decision = router.decide(request, RULES_WEIGHT) { rules(tx, request.choices) }
        return BudgetCategory.fromChoice(decision.choice) to decision
    }

    fun request(tx: ParsedTransaction) = DecisionRequest(
        state = "Card payment of ${"%.2f".format(java.util.Locale.ROOT, tx.amount)} ${tx.currency} at ${tx.merchant}. Bank message: ${compact(tx.rawText, 300)}",
        question = "This purchase is for",
        // "Something else" is left out for the model (NLI models find it true of everything);
        // the rules can still pick it.
        choices = BudgetCategory.entries.filter { it != BudgetCategory.INCOME && it != BudgetCategory.OTHER }.map { it.choice },
    )

    fun rules(tx: ParsedTransaction, choices: List<String>): Decision {
        val r = ScoredRules((choices + BudgetCategory.OTHER.choice).distinct()).add(BudgetCategory.OTHER.choice, 0.5f)
        for ((cat, pts) in MerchantDirectory.score(tx.merchant, tx.rawText)) {
            if (cat.choice in choices) r.add(cat.choice, pts + 1f)
        }
        return r.toDecision()
    }

    companion object {
        const val RULES_WEIGHT = 2f
        /** Kept for compatibility; the directory lives in [MerchantDirectory]. */
        val KEYWORDS get() = MerchantDirectory.KEYWORDS
    }
}
