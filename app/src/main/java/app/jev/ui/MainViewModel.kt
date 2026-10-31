package app.jev.ui

import android.app.Application
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jev.core.money.BudgetCategory
import app.jev.core.money.SpendingInsights
import app.jev.core.money.Subscription
import app.jev.core.scam.ScamReport
import app.jev.core.triage.TriageBucket
import app.jev.data.AppSettings
import app.jev.data.MerchantRule
import app.jev.data.NotificationRecord
import app.jev.data.SenderRule
import app.jev.data.TransactionRecord
import app.jev.jev
import app.jev.ml.ModelState
import app.jev.service.toPoint
import app.jev.work.DigestScheduler
import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.jev.core.money.TransactionParser
import app.jev.core.scam.ScamVerdict
import app.jev.ml.ModelDownloadWorker
import app.jev.ml.SelfTestState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.jev
    private val db = app.db

    val settings: StateFlow<AppSettings> = app.settings.flow
    val modelState: StateFlow<ModelState> = app.models.state

    val notifications = db.notifications().recent(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scamWarnings = db.notifications().scamWarnings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transactions = db.transactions().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subscriptions: StateFlow<List<Subscription>> = db.transactions().all()
        .map { list -> SpendingInsights.detectSubscriptions(list.map { it.toPoint() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val senderRules = db.rules().senderRulesFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _checkResult = MutableStateFlow<ScamReport?>(null)
    val checkResult: StateFlow<ScamReport?> = _checkResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun hasNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    }

    // ---- Scam shield ----
    fun check(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _checkResult.value = withContext(Dispatchers.Default) { app.pipeline.shield.check(text.trim()) }
        }
    }

    fun clearCheck() { _checkResult.value = null }

    // ---- Triage feedback: the user's correction becomes a sender rule ----
    fun teach(record: NotificationRecord, bucket: TriageBucket) = viewModelScope.launch {
        val label = "${record.sender ?: record.title} · ${record.appLabel}"
        db.rules().upsertSender(SenderRule(record.senderKey, label, bucket.name))
        db.notifications().relabelSender(record.senderKey, bucket.name, "You set this")
        _message.value = "Future notifications from ${record.sender ?: record.title} → ${bucket.label}"
    }

    fun forgetRule(rule: SenderRule) = viewModelScope.launch { db.rules().deleteSender(rule.senderKey) }

    // ---- Money ----
    fun recategorize(tx: TransactionRecord, category: BudgetCategory) = viewModelScope.launch {
        db.rules().upsertMerchant(MerchantRule(tx.merchantKey, category.name))
        db.transactions().recategorize(tx.merchantKey, category.name)
        _message.value = "${tx.merchant} is now ${category.label}"
    }

    fun deleteTransaction(tx: TransactionRecord) = viewModelScope.launch { db.transactions().delete(tx.id) }

    // ---- Settings ----
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = app.settings.value
        app.settings.update(transform)
        val after = app.settings.value
        if (before.digestTimes != after.digestTimes) DigestScheduler.schedule(app, after.digestTimes)
        if (before.useModel != after.useModel) viewModelScope.launch { app.models.loadIfPresent(after.useModel) }
    }

    fun importModel(uris: List<Uri>) = viewModelScope.launch {
        _message.value = app.models.import(uris)
        app.models.loadIfPresent(app.settings.value.useModel)
    }

    fun deleteModel() = viewModelScope.launch {
        ModelDownloadWorker.cancel(app)
        app.models.delete()
    }

    // ---- Model download + tests ----
    val modelDownload: StateFlow<WorkInfo?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK)
        .map { infos -> infos.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val selfTest: StateFlow<SelfTestState> = app.models.selfTest

    fun downloadModel(wifiOnly: Boolean) = ModelDownloadWorker.start(app, wifiOnly)
    fun cancelDownload() = ModelDownloadWorker.cancel(app)
    fun runSelfTest() = viewModelScope.launch { app.models.runSelfTest() }

    private val _pipelineTest = MutableStateFlow<PipelineTest?>(null)
    val pipelineTest: StateFlow<PipelineTest?> = _pipelineTest.asStateFlow()

    /**
     * End-to-end check of the live path: Jev posts sample notifications to itself,
     * the notification listener must pick them up and run them through the
     * pipeline, and the results are checked against what we expect.
     */
    fun runPipelineTest() = viewModelScope.launch {
        val checks = mutableListOf<PipelineCheck>()
        fun publish(running: Boolean) { _pipelineTest.value = PipelineTest(checks.toList(), running) }
        publish(true)

        val access = hasNotificationAccess()
        checks += PipelineCheck("Notification access", access, if (access) "Granted" else "Not granted: open Settings → Notification access")
        val canPost = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        checks += PipelineCheck("Jev can show alerts", canPost, if (canPost) "Allowed" else "Notifications are blocked for Jev")
        val model = app.models.state.value
        checks += PipelineCheck(
            "On-device model", model is ModelState.Ready,
            when (model) {
                is ModelState.Ready -> "Loaded, ~${model.msPerDecision} ms per decision"
                is ModelState.Failed -> "Failed to load: ${model.message}"
                ModelState.Loading -> "Still loading"
                ModelState.NotInstalled -> "Not installed: using built-in rules"
            },
            required = false,
        )
        publish(true)
        if (!access || !canPost) { publish(false); return@launch }

        val since = System.currentTimeMillis() - 1_000
        db.notifications().deleteByPackage(app.packageName)
        val samples = listOf(
            Triple("DHL Express", "Your parcel is on hold. Pay the customs fee of QAR 9.50 within 24 hours: dhl-parcel-fee.xyz/pay", Notification.CATEGORY_MESSAGE),
            Triple("SHEIN", "Flash sale! 70% off everything, today only. Shop now!", Notification.CATEGORY_PROMO),
            Triple("Mom", "Call me as soon as you can, it's about dad, he's in the hospital", Notification.CATEGORY_MESSAGE),
        )
        app.notifier.postSelfTestNotifications(samples)
        var records = emptyList<NotificationRecord>()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15_000) {
            records = db.notifications().postedBySince(app.packageName, since)
            if (records.size >= samples.size) break
            delay(250)
        }
        val elapsed = System.currentTimeMillis() - t0
        checks += PipelineCheck(
            "Listener receives notifications", records.size == samples.size,
            "${records.size}/${samples.size} test notifications processed in $elapsed ms" +
                if (records.isEmpty()) ". Try turning notification access off and on again." else "",
        )
        fun find(title: String) = records.firstOrNull { it.title == title }
        find("DHL Express")?.let { r ->
            val ok = r.scamVerdict != null && r.scamVerdict != ScamVerdict.SAFE.name
            checks += PipelineCheck("Scam shield flags a parcel scam", ok, "Verdict: ${r.scamVerdict ?: "not checked"} · flags: ${r.scamFlags.orEmpty()}")
        }
        find("SHEIN")?.let { r ->
            val ok = r.bucket == TriageBucket.DIGEST.name || r.bucket == TriageBucket.SILENCE.name
            checks += PipelineCheck("Triage files a promo away", ok, "${TriageBucket.valueOf(r.bucket).label} (${r.reason})")
        }
        find("Mom")?.let { r ->
            val ok = r.bucket == TriageBucket.URGENT.name
            checks += PipelineCheck("Triage marks an emergency urgent", ok, "${TriageBucket.valueOf(r.bucket).label} (${r.reason})")
        }
        val tx = TransactionParser.parse("Purchase of QAR 24.00 at STARBUCKS VILLAGGIO with card ending 1234", "com.qnb.mobile", 0)
        val cat = tx?.let { app.pipeline.categorizer.categorize(it).first }
        checks += PipelineCheck("Money tracker reads a bank alert", cat == BudgetCategory.DINING, "Starbucks → ${cat?.label ?: "not parsed"}")
        db.notifications().deleteByPackage(app.packageName)
        publish(false)
    }

    fun clearHistory() = viewModelScope.launch {
        db.notifications().clear()
        db.transactions().clear()
        _message.value = "History cleared"
    }
}

data class PipelineCheck(val name: String, val ok: Boolean, val detail: String, val required: Boolean = true)

data class PipelineTest(val checks: List<PipelineCheck>, val running: Boolean) {
    val allRequiredPassed: Boolean get() = checks.filter { it.required }.all { it.ok }
}
