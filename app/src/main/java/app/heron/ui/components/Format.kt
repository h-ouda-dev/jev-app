package app.heron.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import app.heron.core.money.BudgetCategory
import app.heron.core.triage.TriageBucket
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun BudgetCategory.icon(): ImageVector = when (this) {
    BudgetCategory.GROCERIES -> Icons.Rounded.LocalGroceryStore
    BudgetCategory.DINING -> Icons.Rounded.Restaurant
    BudgetCategory.TRANSPORT -> Icons.Rounded.DirectionsCar
    BudgetCategory.SHOPPING -> Icons.Rounded.ShoppingBag
    BudgetCategory.BILLS -> Icons.Rounded.Receipt
    BudgetCategory.SUBSCRIPTIONS -> Icons.Rounded.Subscriptions
    BudgetCategory.ENTERTAINMENT -> Icons.Rounded.Movie
    BudgetCategory.HEALTH -> Icons.Rounded.MedicalServices
    BudgetCategory.TRAVEL -> Icons.Rounded.Flight
    BudgetCategory.TRANSFERS -> Icons.Rounded.SwapHoriz
    BudgetCategory.INCOME -> Icons.Rounded.Savings
    BudgetCategory.OTHER -> Icons.Rounded.MoreHoriz
}

fun TriageBucket.icon(): ImageVector = when (this) {
    TriageBucket.URGENT -> Icons.Rounded.NotificationsActive
    TriageBucket.CAN_WAIT -> Icons.Rounded.Schedule
    TriageBucket.DIGEST -> Icons.Rounded.Inventory2
    TriageBucket.SILENCE -> Icons.Rounded.Bedtime
}

/** One-line explanation of each bucket, used in onboarding, filters and sheets. */
fun TriageBucket.blurb(): String = when (this) {
    TriageBucket.URGENT -> "Interrupts you. People and things that can't wait."
    TriageBucket.CAN_WAIT -> "Stays in your shade, quietly, for when you're ready."
    TriageBucket.DIGEST -> "Held back and delivered together at your digest times."
    TriageBucket.SILENCE -> "Never buzzes. Promos and noise, kept out of sight."
}

private val zone: ZoneId get() = ZoneId.systemDefault()

fun timeOf(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

fun dayLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (d.year == today.year) DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(d)
        else DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(d)
    }
}

/** "now", "5 min", "14:20", "Tue", "3 Oct" */
fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - ms
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000} min"
        d == today -> timeOf(ms)
        d.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(d)
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(d)
    }
}

fun isToday(ms: Long): Boolean = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == LocalDate.now(zone)

fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

fun money(amount: Double, currency: String, decimals: Boolean = true): String =
    if (decimals) "%s %,.2f".format(currency, amount) else "%s %,.0f".format(currency, amount)

fun minutesLabel(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

/** Next digest time label, e.g. "18:30" or "tomorrow 12:30". */
fun nextDigestLabel(times: Set<Int>, now: LocalTime = LocalTime.now()): String? {
    if (times.isEmpty()) return null
    val nowM = now.hour * 60 + now.minute
    val next = times.sorted().firstOrNull { it > nowM }
    return next?.let { minutesLabel(it) } ?: "tomorrow ${minutesLabel(times.min())}"
}
