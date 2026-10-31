package app.heron.core

import app.heron.core.model.DecisionRequest
import app.heron.core.model.ModelConfig
import app.heron.core.model.PairScoringDecisionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun casesBuildAndRulesMostlyPass() {
        val cases = Diagnostics.cases
        assertTrue(cases.size >= 20)
        val rules = cases.count { c -> c.rules(c.request).choice in c.accepted }
        println("Rules pass $rules/${cases.size} self-test cases")
        assertTrue("Rules should pass most self-test cases", rules >= cases.size * 0.8)
    }

    @Test fun reportFlagsBrokenModel() {
        val report = Diagnostics.run({ error("no model") })
        assertEquals(report.total, report.errors)
        assertEquals(false, report.wellFormed)
    }

    @Test fun yesNoStatementUsesSinglePass() {
        var calls = 0
        val cfg = ModelConfig(scoreIndex = 0, contradictionIndex = 2)
        val model = PairScoringDecisionModel(cfg) { pairs, _ ->
            calls += pairs.size
            Array(pairs.size) { floatArrayOf(3f, 0f, -3f) }
        }
        val d = model.decide(DecisionRequest("Message: pay now", "Does it ask for money?", listOf("yes", "no"), statement = "It asks for money."))
        assertEquals(1, calls)
        assertEquals("yes", d.choice)
        assertTrue(d.confidence > 0.99f)
    }
}
