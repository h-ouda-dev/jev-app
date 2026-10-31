package app.jev.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.jev.core.model.DecisionRequest
import app.jev.core.model.DecisionRouter
import app.jev.core.model.JevModelConfig
import app.jev.core.model.PairScoringDecisionModel
import app.jev.core.tokenizer.ByteLevelBpeTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ModelState {
    data object NotInstalled : ModelState
    data object Loading : ModelState
    data class Ready(val sizeMb: Long, val msPerDecision: Long, val details: String) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Owns the on-device model files (app-private storage, never uploaded) and
 * plugs the loaded model into the shared [DecisionRouter].
 */
class ModelManager(private val context: Context, private val router: DecisionRouter) {
    private val dir = File(context.filesDir, "model").apply { mkdirs() }
    private val modelFile = File(dir, "model.onnx")
    private val tokenizerFile = File(dir, "tokenizer.json")
    private val configFile = File(dir, "jev_config.json")

    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var encoder: OnnxEncoder? = null

    val isInstalled: Boolean get() = modelFile.exists() && tokenizerFile.exists()

    suspend fun loadIfPresent(enabled: Boolean) = withContext(Dispatchers.Default) {
        unload()
        if (!enabled || !isInstalled) {
            _state.value = ModelState.NotInstalled
            return@withContext
        }
        _state.value = ModelState.Loading
        try {
            val tokenizer = ByteLevelBpeTokenizer.fromJson(tokenizerFile.readText())
            val config = if (configFile.exists()) JevModelConfig.fromJson(configFile.readText()) else JevModelConfig()
            val enc = OnnxEncoder(modelFile, tokenizer)
            val model = PairScoringDecisionModel(config, enc::forward)
            // Warm up + measure one realistic decision (4 choices, like triage).
            val probe = DecisionRequest(
                "App: WhatsApp. From: Sara. Message: are you coming to the meeting?",
                "This notification is",
                listOf("urgent", "can wait", "digest", "silence"),
            )
            model.decide(probe)
            val t0 = System.nanoTime()
            model.decide(probe)
            val ms = (System.nanoTime() - t0) / 1_000_000
            encoder = enc
            router.model = model
            _state.value = ModelState.Ready(modelFile.length() / (1024 * 1024), ms, enc.description)
        } catch (t: Throwable) {
            Log.e(TAG, "Model load failed", t)
            unload()
            _state.value = ModelState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Copies user-picked files (model .onnx, tokenizer.json, optional jev_config.json) into private storage. */
    suspend fun import(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val imported = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri) ?: continue
            val target = when {
                name.endsWith(".onnx", ignoreCase = true) -> modelFile
                name.equals("tokenizer.json", ignoreCase = true) -> tokenizerFile
                name.contains("config", ignoreCase = true) && name.endsWith(".json", ignoreCase = true) -> configFile
                else -> null
            } ?: continue
            context.contentResolver.openInputStream(uri)?.use { input ->
                val tmp = File(dir, target.name + ".part")
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
                tmp.renameTo(target)
            }
            imported += name
        }
        if (imported.isEmpty()) "No model files recognised. Pick model.onnx and tokenizer.json." else "Imported: ${imported.joinToString()}"
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        unload()
        dir.listFiles()?.forEach { it.delete() }
        _state.value = ModelState.NotInstalled
    }

    private fun unload() {
        router.model = null
        encoder?.close()
        encoder = null
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment

    companion object {
        private const val TAG = "JevModel"
    }
}
