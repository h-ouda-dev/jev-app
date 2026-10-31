package app.heron.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notifications", indices = [Index("postedAt"), Index("bucket")])
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sbnKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val sender: String?,
    val senderKey: String,
    val postedAt: Long,
    /** TriageBucket name. */
    val bucket: String,
    val confidence: Float,
    /** "choice=prob;choice=prob" so the UI can show the full distribution. */
    val probabilities: String,
    val reason: String,
    val decidedBy: String,
    /** True when Heron removed it from the shade (digest / silence with actions enabled). */
    val removedFromShade: Boolean = false,
    val inDigest: Boolean = false,
    val digestDelivered: Boolean = false,
    /** ScamVerdict name, or null when the message wasn't checked. */
    val scamVerdict: String? = null,
    val scamRisk: Float? = null,
    /** Raised RedFlag names, comma separated. */
    val scamFlags: String? = null,
    val latencyMs: Long = 0,
)

@Entity(tableName = "transactions", indices = [Index("timestamp"), Index("merchantKey")])
data class TransactionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val merchantKey: String,
    /** Direction name. */
    val direction: String,
    /** BudgetCategory name. */
    val category: String,
    val confidence: Float,
    val cardHint: String?,
    val sourcePackage: String,
    val rawText: String,
    val timestamp: Long,
    /** Newline separated anomaly messages, empty if none. */
    val anomalies: String = "",
)

@Entity(tableName = "sender_rules")
data class SenderRule(@PrimaryKey val senderKey: String, val label: String, val bucket: String)

@Entity(tableName = "merchant_rules")
data class MerchantRule(@PrimaryKey val merchantKey: String, val category: String)
