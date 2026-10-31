package app.heron

import android.app.Application
import android.util.Log
import androidx.work.ExistingWorkPolicy
import app.heron.core.HeronPipeline
import app.heron.core.money.BudgetCategory
import app.heron.core.money.Direction
import app.heron.core.money.ParsedTransaction
import app.heron.core.money.TransactionParser
import app.heron.data.MerchantRule
import app.heron.core.model.DecisionRouter
import app.heron.data.AppDatabase
import app.heron.data.HistoryImportManager
import app.heron.data.SettingsStore
import app.heron.ml.ModelManager
import app.heron.service.Notifier
import app.heron.work.DigestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: one database, one model, one pipeline shared by the service and the UI. */
class HeronApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val router = DecisionRouter(onModelError = { Log.w("Heron", "Model error, using rules", it) })
    val pipeline = HeronPipeline(router)
    val models: ModelManager by lazy { ModelManager(this, router) }
    val notifier: Notifier by lazy { Notifier(this) }
    val history: HistoryImportManager by lazy { HistoryImportManager(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // KEEP: if WorkManager woke us to run the digest, don't cancel that run.
        DigestScheduler.schedule(this, settings.value.digestTimes, ExistingWorkPolicy.KEEP)
        appScope.launch { models.loadIfPresent(settings.value.useModel) }
        appScope.launch { revalidateTransactions() }
        appScope.launch {
            val cutoff = System.currentTimeMillis() - settings.value.keepDays * 86_400_000L
            db.notifications().deleteOlderThan(cutoff)
        }
    }
}

/**
 * When the transaction parser or merchant directory improves, stored
 * transactions are re-read once: entries that are no longer transactions are
 * removed, merchant names are re-extracted and categories recomputed. Your own
 * category choices always win and follow the merchant to its new name.
 * Statement imports keep their merchant (it came from your own file) but are
 * re-categorised.
 */
private suspend fun HeronApp.revalidateTransactions() {
    val prefs = getSharedPreferences("heron_maintenance", android.content.Context.MODE_PRIVATE)
    if (prefs.getInt("parser_version", 0) >= PARSER_VERSION) return
    val dao = db.transactions()
    val learned = db.rules().merchantRules().associate { it.merchantKey to BudgetCategory.valueOf(it.category) }.toMutableMap()
    val cache = HashMap<String, Pair<BudgetCategory, Float>>()
    for (r in dao.since(0)) {
        val fromStatement = r.sourcePackage.startsWith("statement:")
        val parsed = if (fromStatement) null else TransactionParser.parse(r.rawText, r.sourcePackage, r.timestamp)
        if (!fromStatement && parsed == null) {
            dao.delete(r.id)
            continue
        }
        val merchant = parsed?.merchant ?: r.merchant
        val key = parsed?.merchantKey ?: r.merchantKey
        val userCat = learned[r.merchantKey] ?: learned[key]
        if (userCat != null && key != r.merchantKey && learned[key] == null) {
            db.rules().upsertMerchant(MerchantRule(key, userCat.name))
            learned[key] = userCat
        }
        val (cat, conf) = when {
            userCat != null -> userCat to 1f
            r.direction == Direction.CREDIT.name -> BudgetCategory.INCOME to 1f
            else -> cache.getOrPut(key) {
                val tx = parsed ?: ParsedTransaction(r.amount, r.currency, r.merchant, Direction.DEBIT, r.cardHint, r.sourcePackage, r.rawText, r.timestamp)
                val (c, d) = pipeline.categorizer.categorize(tx, learned)
                c to d.confidence
            }
        }
        if (merchant != r.merchant || key != r.merchantKey || cat.name != r.category) {
            dao.update(r.copy(merchant = merchant, merchantKey = key, category = cat.name, confidence = conf))
        }
    }
    prefs.edit().putInt("parser_version", PARSER_VERSION).apply()
}

/** Bump when parsing or categorisation improves, to refresh stored transactions once. */
private const val PARSER_VERSION = 3

val android.content.Context.heron: HeronApp get() = applicationContext as HeronApp
