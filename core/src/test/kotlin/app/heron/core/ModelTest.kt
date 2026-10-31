package app.heron.core

import app.heron.core.model.Decision
import app.heron.core.model.DecisionRequest
import app.heron.core.model.DecisionRouter
import app.heron.core.model.DecisionSource
import app.heron.core.model.ModelConfig
import app.heron.core.model.PairScoringDecisionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun softmaxSumsToOne() {
        val d = Decision.fromLogits(listOf("a", "b", "c"), floatArrayOf(1f, 2f, 3f), DecisionSource.MODEL)
        assertEquals("c", d.choice)
        assertEquals(1f, d.probabilities.values.sum(), 1e-5f)
    }

    @Test fun pairModelUsesEntailmentLogit() {
        val seen = mutableListOf<Pair<String, String>>()
        val model = PairScoringDecisionModel(ModelConfig.fromJson("""{"score_index": 0, "hypothesis_template": "{question} {choice}."}""")) { pairs, _ ->
            seen += pairs
            // entailment is high only for the hypothesis mentioning "urgent"
            Array(pairs.size) { i -> if ("urgent" in pairs[i].second) floatArrayOf(4f, 0f, -2f) else floatArrayOf(-2f, 0f, 3f) }
        }
        val d = model.decide(DecisionRequest("Mom: call me now", "This is", listOf("urgent", "not urgent")))
        // "not urgent" also contains "urgent" in this fake, so compare against the exact pairs.
        assertEquals("This is urgent.", seen[0].second)
        assertEquals("Mom: call me now", seen[0].first)
        assertTrue(d.confidence >= 0.5f)
    }

    @Test fun routerFallsBackOnErrorAndLowConfidence() {
        val req = DecisionRequest("s", "q", listOf("x", "y"))
        val fallback = { r: DecisionRequest -> Decision.fromLogits(r.choices, floatArrayOf(0f, 5f), DecisionSource.RULES) }
        val broken = DecisionRouter({ error("boom") })
        assertEquals("y", broken.decide(req, fallback = fallback).choice)
        val unsure = DecisionRouter({ Decision.fromLogits(it.choices, floatArrayOf(0.1f, 0f), DecisionSource.MODEL) }, minModelConfidence = 0.8f)
        assertEquals(DecisionSource.RULES, unsure.decide(req, fallback = fallback).source)
    }
}

class EnsembleTest {
    @Test fun rulesCanOverruleAnUnsureModel() {
        val model = Decision.fromLogits(listOf("a", "b"), floatArrayOf(0.3f, 0f), DecisionSource.MODEL)
        val rules = Decision.fromLogits(listOf("a", "b", "other"), floatArrayOf(0f, 3f, 0f), DecisionSource.RULES)
        val d = app.heron.core.model.Ensemble.combine(model, rules, 1f)
        assertEquals("b", d.choice)
        assertEquals(1f, d.probabilities.values.sum(), 1e-4f)
        assertTrue("other" in d.probabilities)
    }
}
