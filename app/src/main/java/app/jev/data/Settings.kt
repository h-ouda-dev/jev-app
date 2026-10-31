package app.jev.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Off = "shadow mode": Jev only labels notifications. On = it removes digest/silence ones from the shade. */
    val actOnNotifications: Boolean = false,
    val scamAlerts: Boolean = true,
    val spendAlerts: Boolean = true,
    val vipNames: Set<String> = emptySet(),
    /** Minutes after midnight. */
    val digestTimes: Set<Int> = setOf(12 * 60 + 30, 18 * 60 + 30),
    val monthlyBudget: Double = 0.0,
    val currency: String = "QAR",
    val useModel: Boolean = true,
    val keepDays: Int = 30,
)

/** Small SharedPreferences-backed settings store exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("jev_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        prefs.edit {
            putBoolean("act", next.actOnNotifications)
            putBoolean("scamAlerts", next.scamAlerts)
            putBoolean("spendAlerts", next.spendAlerts)
            putStringSet("vip", next.vipNames)
            putStringSet("digestTimes", next.digestTimes.map { it.toString() }.toSet())
            putString("budget", next.monthlyBudget.toString())
            putString("currency", next.currency)
            putBoolean("useModel", next.useModel)
            putInt("keepDays", next.keepDays)
        }
        state.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            actOnNotifications = prefs.getBoolean("act", d.actOnNotifications),
            scamAlerts = prefs.getBoolean("scamAlerts", d.scamAlerts),
            spendAlerts = prefs.getBoolean("spendAlerts", d.spendAlerts),
            vipNames = prefs.getStringSet("vip", d.vipNames)?.toSet() ?: d.vipNames,
            digestTimes = prefs.getStringSet("digestTimes", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.digestTimes,
            monthlyBudget = prefs.getString("budget", null)?.toDoubleOrNull() ?: d.monthlyBudget,
            currency = prefs.getString("currency", d.currency) ?: d.currency,
            useModel = prefs.getBoolean("useModel", d.useModel),
            keepDays = prefs.getInt("keepDays", d.keepDays),
        )
    }
}
