package app.jev

import android.app.Application
import android.util.Log
import androidx.work.ExistingWorkPolicy
import app.jev.core.JevPipeline
import app.jev.core.model.DecisionRouter
import app.jev.data.AppDatabase
import app.jev.data.SettingsStore
import app.jev.ml.ModelManager
import app.jev.service.Notifier
import app.jev.work.DigestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: one database, one model, one pipeline shared by the service and the UI. */
class JevApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val router = DecisionRouter(onModelError = { Log.w("Jev", "Model error, using rules", it) })
    val pipeline = JevPipeline(router)
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

val android.content.Context.jev: JevApp get() = applicationContext as JevApp
