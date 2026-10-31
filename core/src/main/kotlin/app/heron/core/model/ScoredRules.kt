package app.heron.core.model

/**
 * Helper for rule-based fallbacks: accumulate evidence points per choice and
 * turn them into a [Decision] with a softmax, so rules and the model produce
 * the same shape of output.
 */
class ScoredRules(private val choices: List<String>, prior: Float = 0f) {
    private val scores = choices.associateWith { prior }.toMutableMap()

    fun add(choice: String, points: Float): ScoredRules {
        require(choice in scores) { "Unknown choice: $choice" }
        scores[choice] = scores.getValue(choice) + points
        return this
    }

    fun score(choice: String): Float = scores.getValue(choice)

    fun toDecision(temperature: Float = 1f): Decision =
        Decision.fromLogits(choices, FloatArray(choices.size) { scores.getValue(choices[it]) }, DecisionSource.RULES, temperature)
}
