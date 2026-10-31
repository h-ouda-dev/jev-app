package app.jev.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.jev.jev
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Delivers the batched "digest" notifications as one summary at the times the user picked. */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.jev
        val dao = app.db.notifications()
        val pending = dao.pendingDigest()
        if (pending.isNotEmpty()) {
            val byApp = pending.groupBy { it.appLabel }.entries.sortedByDescending { it.value.size }
            val summary = byApp.take(4).joinToString(", ") { "${it.key} (${it.value.size})" }
            val lines = pending.takeLast(7).reversed().map { r ->
                val who = r.sender ?: r.title
                "${r.appLabel} · $who: ${r.text.take(60)}"
            }
            app.notifier.digest(pending.size, summary, lines)
            dao.markDigestDelivered(pending.map { it.id })
        }
        // APPEND_OR_REPLACE: don't cancel ourselves while still running.
        DigestScheduler.schedule(applicationContext, app.settings.value.digestTimes, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

object DigestScheduler {
    private const val WORK = "jev-digest"

    /** Schedules a one-shot worker at the next digest time; the worker re-schedules itself. */
    fun schedule(context: Context, times: Set<Int>, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val wm = WorkManager.getInstance(context)
        if (times.isEmpty()) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val delay = delayUntilNext(LocalDateTime.now(), times)
        val req = OneTimeWorkRequestBuilder<DigestWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(WORK, policy, req)
    }

    fun delayUntilNext(now: LocalDateTime, times: Set<Int>): Duration {
        val next = times.sorted().map { m -> now.toLocalDate().atTime(LocalTime.of(m / 60, m % 60)) }
            .firstOrNull { it.isAfter(now.plusSeconds(30)) }
            ?: now.toLocalDate().plusDays(1).atTime(LocalTime.of(times.min() / 60, times.min() % 60))
        return Duration.between(now, next)
    }
}
