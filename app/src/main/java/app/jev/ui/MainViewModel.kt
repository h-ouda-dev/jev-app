package app.jev.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
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
import app.jev.service.JevNotificationListener
import app.jev.service.toPoint
import app.jev.work.DigestScheduler
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

    val notifications = db.notifications().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
        val cn = ComponentName(app, JevNotificationListener::class.java)
        val enabled = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == cn }
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

    fun deleteModel() = viewModelScope.launch { app.models.delete() }

    fun clearHistory() = viewModelScope.launch {
        db.notifications().clear()
        db.transactions().clear()
        _message.value = "History cleared"
    }
}
