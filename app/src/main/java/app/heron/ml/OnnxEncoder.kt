package app.heron.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.heron.core.tokenizer.ByteLevelBpeTokenizer
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * Runs a sequence-classification encoder (ModernBERT-JEV / ModernBERT NLI
 * export) with ONNX Runtime on the phone's CPU. Input: a batch of
 * (premise, hypothesis) pairs. Output: one row of class logits per pair.
 */
class OnnxEncoder(modelFile: File, private val tokenizer: ByteLevelBpeTokenizer) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputNames: Set<String>
    private val opts = OrtSession.SessionOptions()

    init {
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // The S25 Ultra's Snapdragon 8 Elite has 2 prime + 6 performance cores.
        try {
            opts.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            // XNNPACK brings its own thread pool; don't let ORT's pool compete with it.
            opts.setIntraOpNumThreads(1)
        } catch (_: Throwable) {
            opts.setIntraOpNumThreads(4)
        }
        session = env.createSession(modelFile.absolutePath, opts)
        inputNames = session.inputNames
        require("input_ids" in inputNames) { "Model has no input_ids input (found $inputNames)" }
    }

    val description: String get() = "inputs=${inputNames.joinToString()} outputs=${session.outputNames.joinToString()}"

    /**
     * Scores each pair in its own run. The int8 model quantises activations
     * dynamically per tensor, so padding several pairs into one batch would
     * change their results; single runs are exact and still ~15-30 ms each.
     */
    @Synchronized
    fun forward(pairs: List<Pair<String, String>>, maxLength: Int): Array<FloatArray> =
        Array(pairs.size) { i -> runOne(pairs[i].first, pairs[i].second, maxLength) }

    private fun runOne(a: String, b: String, maxLength: Int): FloatArray {
        val e = tokenizer.encodePair(a, b, maxLength)
        val shape = longArrayOf(1, e.length.toLong())
        val tensors = buildMap {
            put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(e.ids), shape))
            if ("attention_mask" in inputNames) put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(e.attentionMask), shape))
            if ("token_type_ids" in inputNames) put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(e.typeIds), shape))
        }
        try {
            session.run(tensors).use { result ->
                @Suppress("UNCHECKED_CAST")
                return when (val v = result.get(0).value) {
                    is Array<*> -> (v as Array<FloatArray>)[0].copyOf()
                    is FloatArray -> floatArrayOf(v[0])
                    else -> error("Unexpected model output type ${v?.javaClass}")
                }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    // Synchronized with forward() so the native session is never freed mid-inference.
    @Synchronized
    override fun close() {
        session.close()
        opts.close()
    }
}
