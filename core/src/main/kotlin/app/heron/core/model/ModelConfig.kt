package app.heron.core.model

import app.heron.core.text.MiniJson
import app.heron.core.text.asMap

/**
 * How a JEV-style encoder is prompted and read. Lives next to the model as
 * `decision_config.json` so different exports (official JEV head, NLI cross-encoder,
 * a fine-tune of your own) can be swapped in without a code change.
 *
 * mode = "pair": every choice is scored as a (premise, hypothesis) pair; the
 *   logit at [scoreIndex] (e.g. "entailment") becomes that choice's score and a
 *   softmax over choices gives the decision. Works with any sequence
 *   classification export, including zero-shot NLI models.
 */
data class ModelConfig(
    val mode: String = "pair",
    val premiseTemplate: String = "{state}",
    val hypothesisTemplate: String = "{question} {choice}",
    /** Output class whose logit is used as the per-choice score; -1 = single-logit model. */
    val scoreIndex: Int = 0,
    /** Class meaning "the statement is false"; enables single-pass yes/no decisions. -1 = none. */
    val contradictionIndex: Int = -1,
    /** If true, use log-softmax over the classes (e.g. log P(entailment)) rather than the raw logit. */
    val useLogProb: Boolean = true,
    val maxLength: Int = 256,
    val temperature: Float = 1f,
) {
    fun premise(r: DecisionRequest, choice: String) = fill(premiseTemplate, r, choice)
    fun hypothesis(r: DecisionRequest, choice: String) = fill(hypothesisTemplate, r, choice)

    private fun fill(t: String, r: DecisionRequest, choice: String) =
        t.replace("{state}", r.state).replace("{question}", r.question).replace("{choice}", choice)

    companion object {
        fun fromJson(json: String): ModelConfig {
            val m = MiniJson.parse(json).asMap() ?: return ModelConfig()
            val d = ModelConfig()
            return ModelConfig(
                mode = m["mode"] as? String ?: d.mode,
                premiseTemplate = m["premise_template"] as? String ?: d.premiseTemplate,
                hypothesisTemplate = m["hypothesis_template"] as? String ?: d.hypothesisTemplate,
                scoreIndex = (m["score_index"] as? Number)?.toInt() ?: d.scoreIndex,
                contradictionIndex = (m["contradiction_index"] as? Number)?.toInt() ?: d.contradictionIndex,
                useLogProb = m["use_log_prob"] as? Boolean ?: d.useLogProb,
                maxLength = (m["max_length"] as? Number)?.toInt() ?: d.maxLength,
                temperature = (m["temperature"] as? Number)?.toFloat() ?: d.temperature,
            )
        }
    }
}

/**
 * Turns any batch encoder (premise/hypothesis pairs -> class logits) into a
 * [DecisionModel]. The Android app supplies the ONNX Runtime forward pass;
 * everything else (prompting, scoring, softmax) lives here and is unit tested.
 */
class PairScoringDecisionModel(
    private val config: ModelConfig,
    private val forward: (pairs: List<Pair<String, String>>, maxLength: Int) -> Array<FloatArray>,
) : DecisionModel {
    override fun decide(request: DecisionRequest): Decision {
        val statement = request.statement
        if (statement != null && request.choices.size == 2 && config.contradictionIndex >= 0) {
            // One forward pass: entailment means choices[0] ("yes"), contradiction means choices[1].
            val logits = forward(listOf(request.state to statement), config.maxLength).single()
            val yesNo = floatArrayOf(logits[config.scoreIndex], logits[config.contradictionIndex])
            return Decision.fromLogits(request.choices, yesNo, DecisionSource.MODEL, config.temperature)
        }
        val pairs = request.choices.map { config.premise(request, it) to config.hypothesis(request, it) }
        val outputs = forward(pairs, config.maxLength)
        require(outputs.size == request.choices.size) { "Model returned ${outputs.size} rows for ${request.choices.size} choices" }
        val scores = FloatArray(outputs.size) { i -> score(outputs[i]) }
        return Decision.fromLogits(request.choices, scores, DecisionSource.MODEL, config.temperature)
    }

    private fun score(logits: FloatArray): Float {
        if (config.scoreIndex < 0 || logits.size == 1) return logits[0]
        val idx = config.scoreIndex.coerceIn(0, logits.size - 1)
        if (!config.useLogProb) return logits[idx]
        val max = logits.max()
        val logSum = max + kotlin.math.ln(logits.sumOf { kotlin.math.exp((it - max).toDouble()) }).toFloat()
        return logits[idx] - logSum
    }
}
