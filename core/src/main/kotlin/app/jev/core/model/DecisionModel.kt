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
    fun decide(request: DecisionRequest, fallback: (DecisionRequest) -> Decision): Decision {
        val m = model ?: return fallback(request)
        return try {
            val d = m.decide(request)
            if (d.confidence >= minModelConfidence) d else fallback(request)
        } catch (t: Throwable) {
            onModelError(t)
            fallback(request)
        }
    }
}
