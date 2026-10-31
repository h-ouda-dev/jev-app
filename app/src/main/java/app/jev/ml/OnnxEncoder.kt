package app.jev.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.jev.core.tokenizer.ByteLevelBpeTokenizer
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

    @Synchronized
    fun forward(pairs: List<Pair<String, String>>, maxLength: Int): Array<FloatArray> {
        val encodings = pairs.map { (a, b) -> tokenizer.encodePair(a, b, maxLength) }
        val batch = encodings.size
        val seq = encodings.maxOf { it.length }
        val ids = LongArray(batch * seq)
        val mask = LongArray(batch * seq)
        val types = LongArray(batch * seq)
        encodings.forEachIndexed { row, e ->
            // ModernBERT's pad id is ignored thanks to the attention mask, so 0 is safe.
            System.arraycopy(e.ids, 0, ids, row * seq, e.length)
            System.arraycopy(e.attentionMask, 0, mask, row * seq, e.length)
            System.arraycopy(e.typeIds, 0, types, row * seq, e.length)
        }
        val shape = longArrayOf(batch.toLong(), seq.toLong())
        val tensors = buildMap {
            put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape))
            if ("attention_mask" in inputNames) put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape))
            if ("token_type_ids" in inputNames) put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape))
        }
        try {
            session.run(tensors).use { result ->
                @Suppress("UNCHECKED_CAST")
                return when (val v = result.get(0).value) {
                    is Array<*> -> (v as Array<FloatArray>).map { it.copyOf() }.toTypedArray()
                    is FloatArray -> Array(batch) { floatArrayOf(v[it]) }
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
