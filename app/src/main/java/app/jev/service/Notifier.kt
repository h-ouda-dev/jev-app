package app.jev.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.jev.R
import app.jev.core.scam.ScamReport
import app.jev.core.scam.ScamVerdict
import app.jev.ui.MainActivity

/** Jev's own notifications: scam warnings, digests and spending flags. */
class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SCAM, "Scam warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Warns when a message looks like a scam"
                },
                NotificationChannel(CH_DIGEST, "Digest", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Batched summary of notifications that could wait"
                },
                NotificationChannel(CH_MONEY, "Spending flags", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Unusual charges, duplicates and subscription price changes"
                },
                NotificationChannel(CH_MODEL, "Model download", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while the on-device model downloads"
                },
                NotificationChannel(CH_SELFTEST, "Self-test", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Test notifications Jev sends itself to check that it is working"
                },
            ),
        )
    }

    fun scamWarning(id: Int, appLabel: String, report: ScamReport) {
        val title = if (report.verdict == ScamVerdict.LIKELY_SCAM) "⚠ Likely scam in $appLabel" else "Be careful: message in $appLabel"
        val lines = report.raisedFlags.joinToString("\n") { "• ${it.flag.title}" }
        post(
            id, CH_SCAM, title,
            "Red flags: " + report.raisedFlags.joinToString { it.flag.title.lowercase() },
            "$lines\n\nDon't tap links, pay, or share codes. Open Jev for the full checklist.",
            openTab = "shield",
            shieldText = report.text,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    fun digest(count: Int, summary: String, lines: List<String>) {
        if (!canPost()) return
        val style = NotificationCompat.InboxStyle().setSummaryText(summary)
        lines.take(7).forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(context, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_jev)
            .setContentTitle("Your digest: $count notifications")
            .setContentText(summary)
            .setStyle(style)
            .setContentIntent(openApp("inbox", null))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(DIGEST_ID, n)
    }

    fun modelReady(summary: String) =
        post(MODEL_READY_ID, CH_MODEL, "Jev model installed", summary, summary, openTab = "settings")

    /**
     * Posts sample notifications that the listener processes like any other app's,
     * proving notification access + the pipeline work end to end.
     */
    fun postSelfTestNotifications(samples: List<Triple<String, String, String>>): Boolean {
        if (!canPost()) return false
        samples.forEachIndexed { i, (title, text, category) ->
            val n = NotificationCompat.Builder(context, CH_SELFTEST)
                .setSmallIcon(R.drawable.ic_stat_jev)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(category)
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_SELF_TEST, true) })
                .setTimeoutAfter(15_000)
                .build()
            NotificationManagerCompat.from(context).notify(SELF_TEST_TAG, SELF_TEST_BASE_ID + i, n)
        }
        return true
    }

    fun spendingFlag(id: Int, title: String, message: String) =
        post(id, CH_MONEY, title, message, message, openTab = "money")

    private fun post(
        id: Int,
        channel: String,
        title: String,
        text: String,
        bigText: String,
        openTab: String,
        shieldText: String? = null,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
    ) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_jev)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(priority)
            .setContentIntent(openApp(openTab, shieldText))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private fun canPost() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(tab: String, shieldText: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
            shieldText?.let { putExtra(MainActivity.EXTRA_CHECK_TEXT, it) }
        }
        return PendingIntent.getActivity(
            context, (tab + shieldText.hashCode()).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CH_SCAM = "scam"
        const val CH_DIGEST = "digest"
        const val CH_MONEY = "money"
        const val CH_MODEL = "model"
        const val CH_SELFTEST = "selftest"
        const val DIGEST_ID = 1
        const val MODEL_PROGRESS_ID = 2
        const val MODEL_READY_ID = 3
        const val SELF_TEST_BASE_ID = 100
        const val SELF_TEST_TAG = "jev-self-test"
        const val EXTRA_SELF_TEST = "app.jev.SELF_TEST"
    }
}
