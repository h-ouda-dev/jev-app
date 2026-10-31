package app.jev.core.money

import app.jev.core.model.Decision
import app.jev.core.model.DecisionRequest
import app.jev.core.model.DecisionRouter
import app.jev.core.model.DecisionSource
import app.jev.core.model.ScoredRules
import app.jev.core.text.compact
import app.jev.core.text.containsPhrase

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
        val hay = (tx.merchant + " " + tx.rawText).lowercase()
        for ((cat, words) in KEYWORDS) {
            val hits = words.count { hay.containsPhrase(it) }
            if (hits > 0 && cat.choice in choices) r.add(cat.choice, 2f + hits)
        }
        return r.toDecision()
    }

    companion object {
        const val RULES_WEIGHT = 1f
        val KEYWORDS: Map<BudgetCategory, List<String>> = mapOf(
            BudgetCategory.GROCERIES to listOf("carrefour", "lulu", "monoprix", "al meera", "megamart", "spinneys", "family food", "safari", "grand hypermarket", "supermarket", "hypermarket", "grocery", "walmart", "tesco", "aldi", "lidl", "kroger", "whole foods", "costco"),
            BudgetCategory.DINING to listOf("restaurant", "cafe", "coffee", "starbucks", "costa", "tim hortons", "mcdonald", "kfc", "burger", "pizza", "shawarma", "talabat", "snoonu", "deliveroo", "uber eats", "doordash", "careem food", "bakery", "grill"),
            BudgetCategory.TRANSPORT to listOf("uber", "careem", "karwa", "lyft", "bolt", "taxi", "fuel", "woqod", "petrol", "shell", "parking", "metro", "toll", "esso"),
            BudgetCategory.SHOPPING to listOf("amazon", "noon", "ikea", "zara", "h&m", "shein", "temu", "aliexpress", "virgin megastore", "jarir", "apple store", "samsung", "electronics", "mall", "store", "boutique", "nike", "adidas"),
            BudgetCategory.BILLS to listOf("ooredoo", "vodafone", "kahramaa", "electricity", "water", "internet", "telecom", "utility", "insurance", "rent", "etisalat", "du ", "verizon", "at&t"),
            BudgetCategory.SUBSCRIPTIONS to listOf("netflix", "spotify", "apple.com/bill", "itunes", "icloud", "google one", "youtube premium", "disney", "osn", "shahid", "amazon prime", "prime video", "chatgpt", "openai", "anthropic", "claude", "adobe", "microsoft 365", "dropbox", "patreon", "subscription", "membership"),
            BudgetCategory.ENTERTAINMENT to listOf("cinema", "vox", "novo", "playstation", "xbox", "steam", "nintendo", "concert", "ticket", "bowling", "theme park"),
            BudgetCategory.HEALTH to listOf("pharmacy", "hospital", "clinic", "medical", "dental", "wellcare", "aster", "boots", "gym", "fitness", "doctor"),
            BudgetCategory.TRAVEL to listOf("qatar airways", "airline", "airways", "emirates", "flydubai", "booking.com", "airbnb", "hotel", "expedia", "agoda", "travel", "duty free"),
            BudgetCategory.TRANSFERS to listOf("transfer", "atm", "withdrawal", "cash", "western union", "remit", "fawran", "sent to"),
        )
    }
}
