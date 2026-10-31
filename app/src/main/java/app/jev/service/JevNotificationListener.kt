package app.jev.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.jev.core.ProcessedNotification
import app.jev.core.model.NotificationEvent
import app.jev.core.money.BudgetCategory
import app.jev.core.money.Direction
import app.jev.core.money.SpendingInsights
import app.jev.core.money.TxPoint
import app.jev.core.scam.ScamVerdict
import app.jev.core.triage.TriageBucket
import app.jev.core.triage.TriagePreferences
import app.jev.data.NotificationRecord
import app.jev.data.TransactionRecord
import app.jev.jev
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one data source for all three features. Every posted notification is
 * turned into a [NotificationEvent] and run through the shared pipeline:
 * triage (urgent / can wait / digest / silence), scam checklist, and money
 * extraction. Everything happens on the phone.
 */
class JevNotificationListener : NotificationListenerService() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 300
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val event = toEvent(sbn) ?: return
        // Apps re-post the same notification constantly (progress, edits); only handle real changes.
        val fingerprint = "${sbn.key}|${event.title}|${event.text}"
        synchronized(recent) {
            if (recent.containsKey(fingerprint)) return
            recent[fingerprint] = sbn.postTime
        }
        scope.launch {
            try {
                handle(sbn, event)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to process ${sbn.packageName}", t)
            }
        }
    }

    private suspend fun handle(sbn: StatusBarNotification, event: NotificationEvent) {
        val app = jev
        val settings = app.settings.value
        val rules = app.db.rules()
        val prefs = TriagePreferences(
            vipNames = settings.vipNames,
            senderRules = rules.senderRules().associate { it.senderKey to TriageBucket.valueOf(it.bucket) },
        )
        val learned = rules.merchantRules().associate { it.merchantKey to BudgetCategory.valueOf(it.category) }
        val result = app.pipeline.process(event, prefs, learned)
        val bucket = result.triage.bucket

        val removable = settings.actOnNotifications && sbn.isClearable &&
            (bucket == TriageBucket.DIGEST || bucket == TriageBucket.SILENCE) &&
            event.category !in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM) &&
            result.scam?.verdict != ScamVerdict.LIKELY_SCAM
        if (removable) cancelNotification(sbn.key)

        app.db.notifications().insert(record(result, removable))

        result.scam?.let { report ->
            if (settings.scamAlerts && report.verdict != ScamVerdict.SAFE) {
                app.notifier.scamWarning(sbn.key.hashCode(), event.appLabel, report)
            }
        }
        saveTransaction(result, settings.spendAlerts)
    }

    private suspend fun saveTransaction(result: ProcessedNotification, alerts: Boolean) {
        val tx = result.transaction ?: return
        val app = jev
        val dao = app.db.transactions()
        // Some banks send the same alert as SMS + app push: keep one.
        if (dao.countDuplicates(tx.rawText, tx.timestamp - 5 * 60_000L) > 0) return
        val history = dao.since(tx.timestamp - 400 * 86_400_000L).map { it.toPoint() }
        val category = result.category ?: BudgetCategory.OTHER
        val point = TxPoint(tx.merchantKey, tx.merchant, tx.amount, tx.currency, tx.timestamp, category, tx.direction)
        val anomalies = SpendingInsights.anomalies(point, history)
        dao.insert(
            TransactionRecord(
                amount = tx.amount, currency = tx.currency, merchant = tx.merchant, merchantKey = tx.merchantKey,
                direction = tx.direction.name, category = category.name,
                confidence = result.categoryDecision?.confidence ?: 0f, cardHint = tx.cardHint,
                sourcePackage = tx.sourcePackage, rawText = tx.rawText, timestamp = tx.timestamp,
                anomalies = anomalies.joinToString("\n") { it.message },
            ),
        )
        if (alerts && anomalies.isNotEmpty()) {
            app.notifier.spendingFlag(tx.rawText.hashCode(), "Check this charge: ${tx.merchant}", anomalies.joinToString("\n") { it.message })
        }
    }

    private fun record(r: ProcessedNotification, removed: Boolean): NotificationRecord {
        val e = r.event
        return NotificationRecord(
            sbnKey = e.key, packageName = e.packageName, appLabel = e.appLabel, title = e.title, text = e.text,
            sender = e.sender, senderKey = e.senderKey, postedAt = e.postedAt,
            bucket = r.triage.bucket.name, confidence = r.triage.decision.confidence,
            probabilities = TriageBucket.entries.joinToString(";") { "${it.name}=${r.triage.decision.probabilityOf(it.choice)}" },
            reason = r.triage.reason, decidedBy = r.triage.decision.source.name,
            removedFromShade = removed, inDigest = r.triage.bucket == TriageBucket.DIGEST,
            scamVerdict = r.scam?.verdict?.name, scamRisk = r.scam?.riskScore,
            scamFlags = r.scam?.raisedFlags?.joinToString(",") { it.flag.name },
            latencyMs = r.elapsedMs,
        )
    }

    private fun toEvent(sbn: StatusBarNotification): NotificationEvent? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (n.category in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE)) return null
        val extras = n.extras ?: return null
        val title = extras.charSeq(Notification.EXTRA_TITLE) ?: extras.charSeq(Notification.EXTRA_TITLE_BIG) ?: ""
        val text = extras.charSeq(Notification.EXTRA_BIG_TEXT) ?: extras.charSeq(Notification.EXTRA_TEXT) ?: lastMessage(extras) ?: ""
        if (title.isBlank() && text.isBlank()) return null
        val conversation = extras.charSeq(Notification.EXTRA_CONVERSATION_TITLE)
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val sender = when {
            conversation != null -> conversation
            n.category == Notification.CATEGORY_MESSAGE || extras.containsKey(Notification.EXTRA_MESSAGES) -> title
            else -> null
        }
        return NotificationEvent(
            key = sbn.key, packageName = sbn.packageName, appLabel = appLabel(sbn.packageName),
            title = title, text = text, category = n.category, postedAt = sbn.postTime,
            sender = sender, isGroupConversation = isGroup,
        )
    }

    private fun lastMessage(extras: Bundle): String? {
        val msgs = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } ?: return null
        val last = msgs.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("text")?.toString()
    }

    private fun Bundle.charSeq(key: String): String? = getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    private val labels = HashMap<String, String>()
    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JevListener"
    }
}

fun TransactionRecord.toPoint() = TxPoint(
    merchantKey, merchant, amount, currency, timestamp,
    runCatching { BudgetCategory.valueOf(category) }.getOrDefault(BudgetCategory.OTHER),
    runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.DEBIT),
)
