package app.jev.core.triage

import app.jev.core.model.Decision
import app.jev.core.model.DecisionRequest
import app.jev.core.model.DecisionRouter
import app.jev.core.model.DecisionSource
import app.jev.core.model.NotificationEvent
import app.jev.core.model.ScoredRules
import app.jev.core.text.containsAnyPhrase
import app.jev.core.text.norm

enum class TriageBucket(val label: String, val choice: String) {
    // The choice text doubles as the NLI hypothesis: "This notification is <choice>."
    URGENT("Urgent", "urgent"),
    CAN_WAIT("Can wait", "not urgent but needs a reply later"),
    DIGEST("Digest", "a low priority update like social media or a newsletter"),
    SILENCE("Silence", "an advertisement or spam");

    companion object {
        fun fromChoice(choice: String) = entries.first { it.choice == choice }
    }
}

/** What the user has taught the triage engine. */
data class TriagePreferences(
    val vipNames: Set<String> = emptySet(),
    /** Learned per-sender rules, keyed by [NotificationEvent.senderKey]. */
    val senderRules: Map<String, TriageBucket> = emptyMap(),
    /** Whole-app rules, keyed by package name. */
    val appRules: Map<String, TriageBucket> = emptyMap(),
)

data class TriageResult(val bucket: TriageBucket, val decision: Decision, val reason: String)

class TriageEngine(private val router: DecisionRouter) {

    fun triage(event: NotificationEvent, prefs: TriagePreferences): TriageResult {
        // 1. Explicit user rules always win: they are the user's own decision.
        prefs.senderRules[event.senderKey]?.let { return fixed(it, "You set this for ${event.sender ?: event.title}") }
        prefs.appRules[event.packageName]?.let { return fixed(it, "You set this for ${event.appLabel}") }

        val isVip = isVip(event, prefs)
        val request = request(event, isVip)
        val decision = router.decide(request, RULES_WEIGHT) { rules(event, isVip) }
        val bucket = TriageBucket.fromChoice(decision.choice)
        // A VIP should never be silenced by a model guess.
        val safeBucket = if (isVip && bucket.ordinal > TriageBucket.CAN_WAIT.ordinal) TriageBucket.CAN_WAIT else bucket
        val reason = when {
            safeBucket != bucket -> "Important contact, never silenced"
            decision.source == DecisionSource.MODEL -> "Jev model, ${(decision.confidence * 100).toInt()}% sure"
            else -> "Built-in rules"
        }
        return TriageResult(safeBucket, decision, reason)
    }

    fun request(event: NotificationEvent, isVip: Boolean) = DecisionRequest(
        state = event.describe() + if (isVip) " The sender is one of my important contacts." else "",
        question = QUESTION,
        choices = TriageBucket.entries.map { it.choice },
    )

    private fun fixed(bucket: TriageBucket, reason: String): TriageResult {
        val probs = TriageBucket.entries.associate { it.choice to if (it == bucket) 1f else 0f }
        return TriageResult(bucket, Decision(bucket.choice, 1f, probs, DecisionSource.RULES), reason)
    }

    private fun isVip(e: NotificationEvent, p: TriagePreferences): Boolean {
        val who = listOfNotNull(e.sender, e.title).joinToString(" ").norm()
        return p.vipNames.any { it.isNotBlank() && who.contains(it.norm().trim()) }
    }

    /** Rule-based fallback used until (or instead of) the on-device model. */
    fun rules(e: NotificationEvent, isVip: Boolean): Decision {
        val r = ScoredRules(TriageBucket.entries.map { it.choice })
        val u = TriageBucket.URGENT.choice
        val w = TriageBucket.CAN_WAIT.choice
        val d = TriageBucket.DIGEST.choice
        val s = TriageBucket.SILENCE.choice
        val text = e.fullText.norm()
        val pkg = e.packageName

        r.add(w, 0.5f)
        if (isVip) r.add(u, 3f)
        when (e.category) {
            "call", "alarm" -> r.add(u, 3f)
            "msg" -> r.add(w, 1.5f)
            "email" -> r.add(w, 1f).add(d, 0.6f)
            "promo" -> r.add(s, 2f).add(d, 1f)
            "social", "recommendation" -> r.add(d, 1.5f)
            "reminder", "event" -> r.add(u, 1f).add(w, 1f)
            "transport", "navigation" -> r.add(w, 1f)
            "progress", "status", "sys", "service" -> r.add(s, 1.5f)
        }
        if (pkg in MESSAGING_APPS && e.category == null) r.add(w, 1.5f)
        if (pkg in SOCIAL_APPS) r.add(d, 1.5f)
        if (pkg in SHOPPING_APPS) r.add(d, 1f).add(s, 0.8f)
        if (text.containsAnyPhrase(URGENT_WORDS)) r.add(u, 2.2f)
        if (OTP_PATTERN.containsMatchIn(text)) r.add(u, 2f)
        if (text.containsAnyPhrase(PROMO_WORDS) || PERCENT_OFF.containsMatchIn(text)) r.add(s, 1.5f).add(d, 1f)
        if (text.containsAnyPhrase(NEWSY_WORDS)) r.add(d, 1.2f)
        if (text.containsAnyPhrase(MONEY_WORDS)) r.add(w, 1f)
        if (e.isGroupConversation && !isVip) r.add(d, 1f)
        return r.toDecision()
    }

    companion object {
        const val QUESTION = "This notification is"
        /** How much the rules count next to the model (see Ensemble). */
        const val RULES_WEIGHT = 1f
        val URGENT_WORDS = listOf(
            "urgent", "asap", "emergency", "call me", "right now", "immediately", "help", "hospital",
            "accident", "missed call", "are you ok", "where are you", "now please", "flight delayed",
            "gate change", "boarding", "fire alarm", "security alert",
        )
        val PROMO_WORDS = listOf(
            "sale", "deal", "offer", "discount", "coupon", "promo", "limited time", "don't miss",
            "free shipping", "unsubscribe", "new arrivals", "flash sale", "cashback", "win ", "exclusive",
        )
        val NEWSY_WORDS = listOf("liked your", "commented on", "new follower", "trending", "breaking", "recommended for you", "newsletter", "weekly", "daily digest", "mentioned you")
        val MONEY_WORDS = listOf("debited", "credited", "purchase", "transaction", "spent", "payment", "card ending")
        val OTP_PATTERN = Regex("(otp|one[- ]time|verification|security|login|passcode|code)[^\\n]{0,40}\\b\\d{4,8}\\b")
        val PERCENT_OFF = Regex("\\d{1,2}\\s?% off")
        val MESSAGING_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.facebook.orca",
            "com.microsoft.teams", "com.slack", "com.discord",
        )
        val SOCIAL_APPS = setOf(
            "com.instagram.android", "com.facebook.katana", "com.twitter.android", "com.zhiliaoapp.musically",
            "com.snapchat.android", "com.linkedin.android", "com.reddit.frontpage", "com.pinterest",
            "com.google.android.youtube",
        )
        val SHOPPING_APPS = setOf(
            "com.amazon.mShop.android.shopping", "com.einnovation.temu", "com.alibaba.aliexpresshd",
            "com.contextlogic.wish", "com.shein.android", "com.noon.buyerapp", "com.talabat",
        )
    }
}
