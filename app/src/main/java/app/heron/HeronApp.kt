package app.heron

import android.app.Application
import android.util.Log
import androidx.work.ExistingWorkPolicy
import app.heron.core.HeronPipeline
import app.heron.core.money.TransactionParser
import app.heron.core.model.DecisionRouter
import app.heron.data.AppDatabase
import app.heron.data.HistoryImportManager
import app.heron.data.SettingsStore
import app.heron.ml.ModelManager
import app.heron.service.Notifier
import app.heron.work.DigestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: one database, one model, one pipeline shared by the service and the UI. */
class HeronApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val router = DecisionRouter(onModelError = { Log.w("Heron", "Model error, using rules", it) })
    val pipeline = HeronPipeline(router)
    val models: ModelManager by lazy { ModelManager(this, router) }
    val notifier: Notifier by lazy { Notifier(this) }
    val history: HistoryImportManager by lazy { HistoryImportManager(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // KEEP: if WorkManager woke us to run the digest, don't cancel that run.
        DigestScheduler.schedule(this, settings.value.digestTimes, ExistingWorkPolicy.KEEP)
        appScope.launch { models.loadIfPresent(settings.value.useModel) }
        appScope.launch { revalidateTransactions() }
        appScope.launch {
            val cutoff = System.currentTimeMillis() - settings.value.keepDays * 86_400_000L
            db.notifications().deleteOlderThan(cutoff)
        }
    }
}

/**
 * After an update that makes the transaction parser stricter, entries it no
 * longer accepts (e.g. bank marketing that slipped through) are removed once.
 * Statement imports are left alone: they come from the user's own file.
 */
private suspend fun HeronApp.revalidateTransactions() {
    val prefs = getSharedPreferences("heron_maintenance", MODE_PRIVATE)
    if (prefs.getInt("parser_version", 0) >= PARSER_VERSION) return
    val dao = db.transactions()
    dao.since(0).filter { r ->
        !r.sourcePackage.startsWith("statement:") &&
            TransactionParser.parse(r.rawText, r.sourcePackage, r.timestamp) == null
    }.forEach { dao.delete(it.id) }
    prefs.edit().putInt("parser_version", PARSER_VERSION).apply()
}

/** Bump when the parser gets stricter, to clean previously stored false positives. */
private const val PARSER_VERSION = 2

val android.content.Context.heron: HeronApp get() = applicationContext as HeronApp
