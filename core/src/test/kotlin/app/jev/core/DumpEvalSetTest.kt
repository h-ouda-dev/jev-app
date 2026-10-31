package app.jev.core

import app.jev.core.model.JevModelConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the self-test decisions as premise/hypothesis pairs so the Python
 * export script can check that a quantised model decides like the original:
 * JEV_DUMP_EVAL=tools/eval_decisions.json ./gradlew :core:test --tests '*DumpEvalSetTest*'
 */
class DumpEvalSetTest {
    @Test fun dump() {
        val out = System.getenv("JEV_DUMP_EVAL")
        assumeTrue(out != null)
        val cfg = JevModelConfig(hypothesisTemplate = "{question} {choice}.")
        fun esc(s: String) = buildString {
            append('"')
            s.forEach { c ->
                when (c) {
                    '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
        val rows = Diagnostics.cases.map { c ->
            val r = c.request
            if (r.statement != null && r.choices.size == 2) {
                """{"name": ${esc(c.feature + ": " + c.name)}, "kind": "binary", "premise": ${esc(r.state)}, "hypotheses": [${esc(r.statement!!)}]}"""
            } else {
                val hyps = r.choices.joinToString(", ") { esc(cfg.hypothesis(r, it)) }
                """{"name": ${esc(c.feature + ": " + c.name)}, "kind": "multi", "premise": ${esc(cfg.premise(r, r.choices[0]))}, "hypotheses": [$hyps]}"""
            }
        }
        File(out!!).writeText("[\n" + rows.joinToString(",\n") + "\n]\n")
        println("Wrote ${rows.size} decisions to $out")
    }
}
