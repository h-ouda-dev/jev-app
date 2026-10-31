package app.heron.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Opens a notification the way tapping it in the shade would: by firing the
 * posting app's own content intent (straight into the right chat, order or
 * email). Heron remembers those intents while it runs, so even notifications
 * it moved out of the shade can still be opened. Falls back to launching the app.
 */
object NotificationActions {
    private const val TAG = "HeronOpen"
    private val intents = object : LinkedHashMap<String, PendingIntent>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > 500
    }
    @Volatile private var listener: WeakReference<HeronNotificationListener>? = null

    fun attach(service: HeronNotificationListener?) {
        listener = service?.let { WeakReference(it) }
    }

    fun remember(key: String, intent: PendingIntent?) {
        if (intent == null) return
        synchronized(intents) { intents[key] = intent }
    }

    enum class Result { OPENED_ITEM, OPENED_APP, NOT_INSTALLED }

    /** Keys of notifications currently in the shade, or null if Heron isn't connected. */
    fun activeKeys(): Set<String>? = try {
        listener?.get()?.activeNotifications?.map { it.key }?.toSet()
    } catch (_: Exception) {
        null
    }

    /** Re-processes everything in the shade; null if Heron isn't connected. */
    fun rescan(): kotlinx.coroutines.Job? = listener?.get()?.rescan()

    /** Removes it from the shade, like tapping an auto-cancel notification would. */
    fun dismiss(key: String) {
        try { listener?.get()?.cancelNotification(key) } catch (_: Exception) { }
    }

    fun open(context: Context, packageName: String, key: String): Result {
        val pending = synchronized(intents) { intents[key] }
            ?: try {
                listener?.get()?.activeNotifications?.firstOrNull { it.key == key }?.notification?.contentIntent
            } catch (_: Exception) {
                null
            }
        if (pending != null) {
            try {
                val options = ActivityOptions.makeBasic().apply {
                    // Heron is in the foreground because the user just tapped: let the other app's activity start.
                    if (Build.VERSION.SDK_INT >= 34) {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }
                }
                pending.send(context, 0, null, null, null, null, options.toBundle())
                return Result.OPENED_ITEM
            } catch (e: Exception) {
                Log.w(TAG, "Content intent for $packageName failed, opening the app instead", e)
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return Result.NOT_INSTALLED
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Result.OPENED_APP
    }
}
