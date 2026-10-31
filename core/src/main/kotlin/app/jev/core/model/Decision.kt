package app.jev.core.model

/**
 * A single Jev-style decision request: a description of the current [state],
 * a [question] about it, and the closed set of [choices] the answer must come from.
 */
data class DecisionRequest(
    val state: String,
    val question: String,
    val choices: List<String>,
) {
    init {
        require(choices.size >= 2) { "A decision needs at least two choices" }
        require(choices.toSet().size == choices.size) { "Choices must be unique" }
    }
}

/** Where a decision came from, so the UI can be honest about it. */
enum class DecisionSource { MODEL, RULES }

/**
 * The output of a decision: the [choice] picked, its [confidence] and the full
 * [probabilities] distribution over all choices (sums to ~1).
 */
data class Decision(
    val choice: String,
    val confidence: Float,
    val probabilities: Map<String, Float>,
    val source: DecisionSource,
) {
    fun probabilityOf(choice: String): Float = probabilities[choice] ?: 0f

    companion object {
        /** Builds a decision from raw per-choice scores (logits) using a softmax. */
        fun fromLogits(
            choices: List<String>,
            logits: FloatArray,
            source: DecisionSource,
            temperature: Float = 1f,
        ): Decision {
            require(choices.size == logits.size) { "One logit per choice is required" }
            val probs = softmax(logits, temperature)
            val best = probs.indices.maxBy { probs[it] }
            return Decision(
                choice = choices[best],
                confidence = probs[best],
                probabilities = choices.zip(probs.toList()).toMap(),
                source = source,
            )
        }

        fun softmax(logits: FloatArray, temperature: Float = 1f): FloatArray {
            val t = if (temperature <= 0f) 1f else temperature
            val max = logits.max()
            val exps = FloatArray(logits.size) { kotlin.math.exp(((logits[it] - max) / t).toDouble()).toFloat() }
            val sum = exps.sum()
            return FloatArray(exps.size) { exps[it] / sum }
        }
    }
}
