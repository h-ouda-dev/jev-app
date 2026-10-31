package app.heron.ml

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.heron.BuildConfig
import app.heron.R
import app.heron.core.DiagnosticReport
import app.heron.heron
import app.heron.service.Notifier
import java.io.IOException

/**
 * Downloads, verifies, installs, loads and self-tests the model in the
 * background, so the user can leave the app while the model (a few hundred MB) arrives.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.heron
        val downloader = ModelDownloader(inputData.getString(KEY_URL) ?: BuildConfig.MODEL_BASE_URL)
        try {
            safeForeground(0, 0, "Preparing…")
            setProgress(workDataOf(KEY_STAGE to STAGE_DOWNLOADING))
            val manifest = downloader.fetchManifest()
            var lastMb = -1L
            downloader.download(manifest, app.models.stagingDir) { done, total ->
                val mb = done / (1024 * 1024)
                if (mb != lastMb) {
                    lastMb = mb
                    setProgress(workDataOf(KEY_STAGE to STAGE_DOWNLOADING, KEY_DONE to done, KEY_TOTAL to total))
                    safeForeground(done, total, "%d / %d MB".format(mb, total / (1024 * 1024)))
                }
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_INSTALLING))
            safeForeground(1, 1, "Installing…")
            app.models.installFromStaging(manifest)
            app.settings.update { it.copy(useModel = true) }
            app.models.loadIfPresent(true)
            val state = app.models.state.value
            if (state !is ModelState.Ready) {
                return Result.failure(workDataOf(KEY_ERROR to "Model downloaded but failed to load: ${(state as? ModelState.Failed)?.message}"))
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_TESTING))
            safeForeground(1, 1, "Running self-test…")
            val test = app.models.runSelfTest()
            val report = (test as? SelfTestState.Done)?.report
            app.notifier.modelReady(summary(report, state))
            return Result.success(workDataOf(KEY_SUMMARY to summary(report, state)))
        } catch (e: ChecksumException) {
            app.models.stagingDir.deleteRecursively()
            return Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: IOException) {
            Log.w(TAG, "Download interrupted, will retry", e)
            // Partial files are kept, so the retry resumes where it stopped.
            return if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Network error")))
        }
    }

    private fun summary(report: DiagnosticReport?, state: ModelState.Ready): String =
        if (report == null) "Model ready · ~${state.msPerDecision} ms per decision"
        else "Self-test ${report.passed}/${report.total} passed · ~${report.medianMillis} ms per decision"

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, "Preparing…")

    private suspend fun safeForeground(done: Long, total: Long, text: String) {
        try {
            setForeground(foregroundInfo(done, total, text))
        } catch (t: Throwable) {
            // Starting a foreground service can be refused (e.g. from background); the download still runs.
            Log.d(TAG, "Foreground not available: ${t.message}")
        }
    }

    private fun foregroundInfo(done: Long, total: Long, text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_MODEL)
            .setSmallIcon(R.drawable.ic_stat_heron)
            .setContentTitle("Downloading Heron AI")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .build()
        return ForegroundInfo(Notifier.MODEL_PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "HeronModelDownload"
        const val WORK = "heron-model-download"
        const val KEY_URL = "url"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_SUMMARY = "summary"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_INSTALLING = "installing"
        const val STAGE_TESTING = "testing"

        fun start(context: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
