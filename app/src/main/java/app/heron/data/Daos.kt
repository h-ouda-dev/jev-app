package app.heron.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(r: NotificationRecord): Long

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notifications WHERE scamVerdict IS NOT NULL AND scamVerdict != 'SAFE' ORDER BY postedAt DESC LIMIT 100")
    fun scamWarnings(): Flow<List<NotificationRecord>>

    @Query("UPDATE notifications SET handledAt = :at WHERE sbnKey = :key AND handledAt IS NULL")
    suspend fun markHandled(key: String, at: Long)

    /** Anything no longer in the shade (and not moved out by Heron itself) has been dealt with. */
    @Query("UPDATE notifications SET handledAt = :at WHERE handledAt IS NULL AND removedFromShade = 0 AND postedAt >= :since AND sbnKey NOT LIKE 'demo-%' AND sbnKey NOT IN (:activeKeys)")
    suspend fun markHandledUnlessActive(activeKeys: List<String>, since: Long, at: Long)

    @Query("UPDATE notifications SET handledAt = :at WHERE handledAt IS NULL AND removedFromShade = 0 AND postedAt >= :since")
    suspend fun markAllHandledSince(since: Long, at: Long)

    @Query("SELECT * FROM notifications WHERE inDigest = 1 AND digestDelivered = 0 ORDER BY postedAt ASC")
    suspend fun pendingDigest(): List<NotificationRecord>

    @Query("UPDATE notifications SET digestDelivered = 1 WHERE id IN (:ids)")
    suspend fun markDigestDelivered(ids: List<Long>)

    @Query("UPDATE notifications SET bucket = :bucket, confidence = 1, reason = :reason, decidedBy = 'YOU' WHERE senderKey = :senderKey")
    suspend fun relabelSender(senderKey: String, bucket: String, reason: String)

    @Query("SELECT * FROM notifications WHERE packageName = :pkg AND postedAt >= :since")
    suspend fun postedBySince(pkg: String, since: Long): List<NotificationRecord>

    @Query("DELETE FROM notifications WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Query("DELETE FROM notifications WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM notifications")
    suspend fun clear()
}

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(t: TransactionRecord): Long

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun all(): Flow<List<TransactionRecord>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<TransactionRecord>

    @Insert
    suspend fun insertAll(t: List<TransactionRecord>)

    @Query("SELECT COUNT(*) FROM transactions WHERE rawText = :raw AND timestamp > :since")
    suspend fun countDuplicates(raw: String, since: Long): Int

    @androidx.room.Update
    suspend fun update(t: TransactionRecord)

    @Query("UPDATE transactions SET category = :category, confidence = 1 WHERE merchantKey = :merchantKey")
    suspend fun recategorize(merchantKey: String, category: String)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clear()

    /** Imported = from SMS history or a CSV statement (not seen live as a notification). */
    @Query("DELETE FROM transactions WHERE sourcePackage LIKE 'sms:%' OR sourcePackage LIKE 'sms-sender:%' OR sourcePackage LIKE 'statement:%'")
    suspend fun deleteImported(): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE sourcePackage LIKE 'sms:%' OR sourcePackage LIKE 'sms-sender:%' OR sourcePackage LIKE 'statement:%'")
    fun importedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions")
    fun count(): Flow<Int>
}

@Dao
interface RuleDao {
    @Upsert
    suspend fun upsertSender(r: SenderRule)

    @Query("DELETE FROM sender_rules WHERE senderKey = :key")
    suspend fun deleteSender(key: String)

    @Query("SELECT * FROM sender_rules")
    suspend fun senderRules(): List<SenderRule>

    @Query("SELECT * FROM sender_rules ORDER BY label")
    fun senderRulesFlow(): Flow<List<SenderRule>>

    @Upsert
    suspend fun upsertMerchant(r: MerchantRule)

    @Query("SELECT * FROM merchant_rules")
    suspend fun merchantRules(): List<MerchantRule>
}
