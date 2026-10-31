package app.jev.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A platform-independent snapshot of one posted notification. */
data class NotificationEvent(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    /** Android Notification.category (msg, call, email, promo, social, alarm, ...), if set. */
    val category: String? = null,
    val postedAt: Long = System.currentTimeMillis(),
    /** Conversation/person name when the app provides one (MessagingStyle). */
    val sender: String? = null,
    val isGroupConversation: Boolean = false,
) {
    val fullText: String get() = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ")

    /** Stable key used for per-sender learned rules. */
    val senderKey: String get() = "$packageName|${(sender ?: title).trim().lowercase(Locale.ROOT)}"

    fun describe(zone: ZoneId = ZoneId.systemDefault()): String {
        val time = DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(postedAt).atZone(zone))
        return buildString {
            append("App: ").append(appLabel).append(". ")
            sender?.let { append("From: ").append(it).append(". ") }
            if (title.isNotBlank() && title != sender) append("Title: ").append(title).append(". ")
            append("Message: ").append(app.jev.core.text.compact(text, 400)).append(". ")
            category?.let { append("Category: ").append(it).append(". ") }
            append("Time: ").append(time).append('.')
        }
    }
}
