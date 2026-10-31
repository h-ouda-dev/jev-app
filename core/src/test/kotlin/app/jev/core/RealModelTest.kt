package app.jev.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.jev.core.model.JevModelConfig
import app.jev.core.model.PairScoringDecisionModel
import app.jev.core.text.MiniJson
import app.jev.core.text.asList
import app.jev.core.text.asMap
import app.jev.core.tokenizer.ByteLevelBpeTokenizer
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.LongBuffer

/**
 * Runs the exported model through exactly the code path the phone uses
 * (Kotlin tokenizer -> ONNX Runtime -> PairScoringDecisionModel -> features).
 * Enabled by JEV_MODEL_DIR; CI sets it after exporting the model.
 */
class RealModelTest {
    companion object {
        private val dir = System.getenv("JEV_MODEL_DIR")?.let(::File)
        private lateinit var env: OrtEnvironment
        private lateinit var session: OrtSession
        private lateinit var tokenizer: ByteLevelBpeTokenizer
        private lateinit var config: JevModelConfig

        @BeforeClass @JvmStatic fun load() {
            if (dir == null || !File(dir, "model.onnx").exists()) return
            env = OrtEnvironment.getEnvironment()
            session = env.createSession(File(dir, "model.onnx").absolutePath, OrtSession.SessionOptions())
            tokenizer = ByteLevelBpeTokenizer.fromJson(File(dir, "tokenizer.json").readText())
            config = JevModelConfig.fromJson(File(dir, "jev_config.json").readText())
        }

        val loaded: Boolean get() = ::session.isInitialized

        @AfterClass @JvmStatic fun close() {
            if (::session.isInitialized) session.close()
        }

        fun forward(pairs: List<Pair<String, String>>, maxLength: Int): Array<FloatArray> {
            val enc = pairs.map { tokenizer.encodePair(it.first, it.second, maxLength) }
            val seq = enc.maxOf { it.length }
            val ids = LongArray(enc.size * seq)
            val mask = LongArray(enc.size * seq)
            enc.forEachIndexed { r, e ->
                System.arraycopy(e.ids, 0, ids, r * seq, e.length)
                System.arraycopy(e.attentionMask, 0, mask, r * seq, e.length)
            }
            val shape = longArrayOf(enc.size.toLong(), seq.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { i ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { m ->
                    session.run(mapOf("input_ids" to i, "attention_mask" to m)).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        return (res.get(0).value as Array<FloatArray>)
                    }
                }
            }
        }
    }

    private fun requireModel() = assumeTrue("JEV_MODEL_DIR not set: skipping real-model tests", loaded)

    @Test fun kotlinTokenizerMatchesHuggingFace() {
        requireModel()
        val ref = MiniJson.parse(File(dir, "reference.json").readText()).asMap()!!
        val cases = ref["tokenization"].asList()!!
        for (c in cases) {
            val m = c.asMap()!!
            val expected = m["ids"].asList()!!.map { (it as Number).toLong() }.toLongArray()
            val got = tokenizer.encodePair(m["a"] as String, m["b"] as String, 512).ids
            assertArrayEquals("Tokenization differs for: ${m["a"]} / ${m["b"]}", expected, got)
        }
        println("Tokenizer: ${cases.size} reference pairs match Hugging Face exactly")
    }

    @Test fun logitsMatchPythonOnnxRuntime() {
        requireModel()
        val ref = MiniJson.parse(File(dir, "reference.json").readText()).asMap()!!
        val cases = ref["tokenization"].asList()!!.map { it.asMap()!! }
        val got = forward(cases.map { it["a"] as String to it["b"] as String }, 512)
        cases.forEachIndexed { i, c ->
            val expected = c["logits"].asList()!!.map { (it as Number).toFloat() }
            expected.forEachIndexed { j, v -> assertEquals("logit[$i][$j]", v, got[i][j], 0.05f) }
        }
    }

    @Test fun diagnosticsPassWithRealModel() {
        requireModel()
        val model = PairScoringDecisionModel(config, ::forward)
        model.decide(Diagnostics.cases.first().request) // warm-up
        val report = Diagnostics.run(model)
        println("=== Jev self-test with ${dir!!.name} ===")
        report.results.forEach { r ->
            val p = r.decision?.let { "%.2f".format(it.confidence) } ?: "ERR"
            println("${if (r.passed) "PASS" else "FAIL"}  [${r.case.feature}] ${r.case.name}: ${r.decision?.choice} ($p, ${r.millis} ms)" +
                if (r.passed) "" else "  expected ${r.case.accepted}")
        }
        report.byFeature().forEach { (f, s) -> println("$f: ${s.first}/${s.second}") }
        println("Model accuracy ${(report.accuracy * 100).toInt()}%  (rules ${(report.rulesAccuracy * 100).toInt()}%), median ${report.medianMillis} ms/decision on CI CPU")
        assertEquals("Model errors", 0, report.errors)
        assertTrue("Decisions must be well-formed distributions", report.wellFormed)
        assertTrue("Model accuracy too low: ${report.accuracy}", report.accuracy >= MIN_ACCURACY)
    }
}

/** Floor for the zero-shot model on the built-in self-test; raised as the model improves. */
const val MIN_ACCURACY = 0.6f
