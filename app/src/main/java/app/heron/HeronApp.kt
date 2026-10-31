package app.heron

import android.app.Application
import android.util.Log
import androidx.work.ExistingWorkPolicy
import app.heron.core.HeronPipeline
import app.heron.core.model.DecisionRouter
import app.heron.data.AppDatabase
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

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // KEEP: if WorkManager woke us to run the digest, don't cancel that run.
        DigestScheduler.schedule(this, settings.value.digestTimes, ExistingWorkPolicy.KEEP)
        appScope.launch { models.loadIfPresent(settings.value.useModel) }
        appScope.launch {
            val cutoff = System.currentTimeMillis() - settings.value.keepDays * 86_400_000L
            db.notifications().deleteOlderThan(cutoff)
        }
    }
}

val android.content.Context.heron: HeronApp get() = applicationContext as HeronApp
