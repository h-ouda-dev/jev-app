package app.heron.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Telephony
import app.heron.HeronApp
import app.heron.core.money.BudgetCategory
import app.heron.core.money.HistoryImporter
import app.heron.core.money.ImportSummary
import app.heron.core.money.ParsedTransaction
import app.heron.core.money.RawMessage
import app.heron.core.money.StatementCsv
import app.heron.service.toPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Brings past spending into Heron: bank SMS from the phone's inbox, or a CSV
 * statement the user picks. All parsing happens here, on the phone; only the
 * extracted transactions are stored, never the other messages.
 */
class HistoryImportManager(private val app: HeronApp) {
    private val importer = HistoryImporter(app.pipeline.categorizer)

    /** Reads SMS received in the last [months] months. Caller must hold READ_SMS. */
    suspend fun importSms(months: Int, onProgress: (String, Float?) -> Unit): ImportSummary = withContext(Dispatchers.IO) {
        onProgress("Reading messages…", null)
        val since = System.currentTimeMillis() - months * 30L * 86_400_000L
        val messages = mutableListOf<RawMessage>()
        app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(1) ?: continue
                messages += RawMessage(c.getString(0) ?: "", body, c.getLong(2))
            }
        }
        onProgress("Finding transactions in ${messages.size} messages…", null)
        val txs = importer.fromMessages(messages)
        store(txs, scanned = messages.size, sources = txs.map { it.sourcePackage }.distinct().size, onProgress = onProgress)
    }

    suspend fun importCsv(context: Context, uri: Uri, onProgress: (String, Float?) -> Unit): ImportSummary = withContext(Dispatchers.IO) {
        onProgress("Reading statement…", null)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "statement.csv"
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Couldn't open $name")
        val parsed = StatementCsv.parse(text, app.settings.value.currency)
        val txs = importer.fromStatement(parsed.rows, name)
        store(txs, scanned = parsed.rows.size + parsed.skipped, sources = 1, onProgress = onProgress)
    }

    private suspend fun store(
        txs: List<ParsedTransaction>,
        scanned: Int,
        sources: Int,
        onProgress: (String, Float?) -> Unit,
    ): ImportSummary {
        val dao = app.db.transactions()
        val from = txs.minOfOrNull { it.timestamp }
        val existingRecords = if (from != null) dao.since(from - 400 * 86_400_000L) else emptyList()
        val existing = existingRecords.map { it.toPoint() }
        val learned = app.db.rules().merchantRules().associate { it.merchantKey to BudgetCategory.valueOf(it.category) }
        // Already stored (e.g. seen live as a notification, or imported before)?
        val isDuplicate = { t: ParsedTransaction ->
            existingRecords.any { e ->
                abs(e.amount - t.amount) < 0.005 && e.direction == t.direction.name &&
                    abs(e.timestamp - t.timestamp) < (if (t.sourcePackage.startsWith("statement:")) 36 * 3_600_000L else 10 * 60_000L) &&
                    (e.merchantKey == t.merchantKey || t.sourcePackage.startsWith("statement:"))
            }
        }
        val (imported, duplicates) = importer.process(txs, existing, learned, isDuplicate) { done, total ->
            onProgress("Sorting $total merchants into categories…", done.toFloat() / total)
        }
        onProgress("Saving…", null)
        dao.insertAll(
            imported.map { i ->
                val t = i.tx
                TransactionRecord(
                    amount = t.amount, currency = t.currency, merchant = t.merchant, merchantKey = t.merchantKey,
                    direction = t.direction.name, category = i.category.name, confidence = i.confidence,
                    cardHint = t.cardHint, sourcePackage = t.sourcePackage, rawText = t.rawText, timestamp = t.timestamp,
                    anomalies = i.anomalies.joinToString("\n") { it.message },
                )
            },
        )
        return ImportSummary(
            scanned = scanned,
            found = imported.size,
            duplicates = duplicates,
            merchants = imported.map { it.tx.merchantKey }.distinct().size,
            sources = sources,
            from = imported.minOfOrNull { it.tx.timestamp },
            to = imported.maxOfOrNull { it.tx.timestamp },
        )
    }
}
