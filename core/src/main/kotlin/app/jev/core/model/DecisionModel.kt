package app.jev.core.model

/**
 * Anything that can answer a [DecisionRequest]. On the phone this is the
 * on-device ONNX JEV model; in tests (or before a model is installed) every
 * feature falls back to its own rule-based scorer.
 */
fun interface DecisionModel {
    fun decide(request: DecisionRequest): Decision
}

/**
 * Asks the model when one is installed and confident enough, otherwise uses
 * the feature's rule-based [fallback]. Errors from the model never crash the
 * notification pipeline: they fall back to rules.
 */
class DecisionRouter(
    @Volatile var model: DecisionModel? = null,
    var minModelConfidence: Float = 0f,
    private val onModelError: (Throwable) -> Unit = {},
) {
    /**
     * [rulesWeight] > 0 blends the model with the rules (product of experts):
     * useful where a zero-shot model is weaker than hand-written evidence.
     */
    fun decide(request: DecisionRequest, rulesWeight: Float = 0f, fallback: (DecisionRequest) -> Decision): Decision {
        val m = model ?: return fallback(request)
        return try {
            val d = m.decide(request)
            val out = if (rulesWeight > 0f) Ensemble.combine(d, fallback(request), rulesWeight) else d
            if (out.confidence >= minModelConfidence) out else fallback(request)
        } catch (t: Throwable) {
            onModelError(t)
            fallback(request)
        }
    }
}

object Ensemble {
    /**
     * score(c) = ln P_model(c) + w * ln P_rules(c), renormalised. Choices only
     * the rules know (e.g. "something else") keep the rules' probability alone.
     */
    fun combine(model: Decision, rules: Decision, rulesWeight: Float): Decision {
        val choices = (model.probabilities.keys + rules.probabilities.keys).toList()
        val eps = 1e-6f
        val logits = FloatArray(choices.size) { i ->
            val c = choices[i]
            val pm = model.probabilities[c]
            val pr = rules.probabilities[c] ?: eps
            if (pm == null) {
                // Not offered to the model: judge it on rules alone, on the same scale.
                (1 + rulesWeight) * kotlin.math.ln(pr.coerceAtLeast(eps))
            } else {
                kotlin.math.ln(pm.coerceAtLeast(eps)) + rulesWeight * kotlin.math.ln(pr.coerceAtLeast(eps))
            }
        }
        return Decision.fromLogits(choices, logits, DecisionSource.MODEL, 1f + rulesWeight)
    }
}
