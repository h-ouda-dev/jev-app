package app.heron.core.scam

import app.heron.core.model.Decision
import app.heron.core.model.DecisionRequest
import app.heron.core.model.DecisionRouter
import app.heron.core.model.ScoredRules
import app.heron.core.text.compact
import app.heron.core.text.matchedPhrases
import app.heron.core.text.norm

/** One yes/no question on the red-flag checklist. */
enum class RedFlag(val title: String, val question: String, val statement: String, val weight: Float) {
    URGENCY("Creates urgency or pressure", "Does this message pressure the reader to act immediately?",
        "This message pressures the reader to act immediately.", 1.0f),
    PAYMENT("Asks for money or payment", "Does this message ask the reader to pay, send money, buy gift cards or transfer crypto?",
        "This message asks the reader to pay or send money.", 1.4f),
    CODE_OR_PASSWORD("Asks for a code or password", "Does this message ask the reader to share a password, PIN or verification code?",
        "This message asks the reader to share a password, PIN or verification code.", 2.0f),
    IMPERSONATION("Pretends to be a bank, courier or official body", "Does this message claim to come from a bank, delivery company, government agency or well-known brand?",
        "This message claims to come from a bank, delivery company, government agency or big company.", 1.2f),
    SUSPICIOUS_LINK("Contains a suspicious link", "Does this message contain a suspicious link?",
        "This message contains a suspicious link.", 1.5f),
    TOO_GOOD("Prize, refund or too good to be true", "Does this message promise a prize, refund, free money or a deal that is too good to be true?",
        "This message promises a prize, refund or free money.", 1.2f),
    THREAT("Threatens a penalty", "Does this message threaten account suspension, a fine, legal action or arrest?",
        "This message threatens account suspension, a fine, legal action or arrest.", 1.2f),
    MOVE_CHANNEL("Asks to move to another app or number", "Does this message ask the reader to continue on another app, phone number or website?",
        "This message asks the reader to contact someone on another app, phone number or website.", 0.8f),
}

data class FlagCheck(val flag: RedFlag, val raised: Boolean, val probability: Float, val evidence: List<String>)

enum class ScamVerdict(val label: String) { SAFE("Looks safe"), SUSPICIOUS("Be careful"), LIKELY_SCAM("Likely scam") }

data class ScamReport(
    val text: String,
    val checks: List<FlagCheck>,
    val links: List<LinkFinding>,
    val riskScore: Float,
    val verdict: ScamVerdict,
) {
    val raisedFlags: List<FlagCheck> get() = checks.filter { it.raised }
}

/**
 * Runs a message through the red-flag checklist. Each question is a separate
 * yes/no Jev decision, so the result is explainable even though the model
 * itself never writes an explanation.
 */
class ScamShield(private val router: DecisionRouter) {

    /** [sourcePackage] lets us avoid flagging a brand's own app as "impersonating" itself. */
    fun check(text: String, sourcePackage: String? = null, senderIsKnownContact: Boolean = false): ScamReport {
        val links = LinkInspector.inspect(text)
        val checks = RedFlag.entries.map { flag ->
            val request = request(text, flag)
            val evidence = evidence(flag, text, links, sourcePackage)
            val decision = when (flag) {
                // Link analysis is deterministic and better than any language model guess.
                RedFlag.SUSPICIOUS_LINK -> rulesFor(flag, evidence)
                else -> router.decide(request, RULES_WEIGHT) { rulesFor(flag, evidence) }
            }
            val pYes = decision.probabilityOf(YES)
            FlagCheck(flag, pYes >= 0.5f, pYes, evidence)
        }
        // Only raised flags count, so many weak "probably not" answers don't add up to a false alarm.
        var risk = checks.filter { it.raised }.sumOf { (it.probability * it.flag.weight).toDouble() }.toFloat()
        val raised = checks.filter { it.raised }.map { it.flag }.toSet()
        // Classic combinations are much stronger than the parts.
        if (RedFlag.IMPERSONATION in raised && RedFlag.SUSPICIOUS_LINK in raised) risk += 1.5f
        if (RedFlag.URGENCY in raised && (RedFlag.PAYMENT in raised || RedFlag.CODE_OR_PASSWORD in raised)) risk += 1f
        if (senderIsKnownContact) risk *= 0.6f
        val verdict = when {
            risk >= 3.5f || RedFlag.CODE_OR_PASSWORD in raised && links.any { it.suspicious } -> ScamVerdict.LIKELY_SCAM
            risk >= 1.8f || raised.size >= 2 -> ScamVerdict.SUSPICIOUS
            else -> ScamVerdict.SAFE
        }
        return ScamReport(text, checks, links, risk, verdict)
    }

    fun request(text: String, flag: RedFlag) =
        DecisionRequest("Message: " + compact(text, 500), flag.question, YES_NO, statement = flag.statement)

    private fun evidence(flag: RedFlag, text: String, links: List<LinkFinding>, pkg: String?): List<String> {
        val t = text.norm()
        return when (flag) {
            RedFlag.URGENCY -> t.matchedPhrases(URGENCY_WORDS)
            RedFlag.PAYMENT -> t.matchedPhrases(PAYMENT_WORDS)
            RedFlag.CODE_OR_PASSWORD -> ASK_FOR_CODE.findAll(t).map { it.value.trim() }.toList()
            RedFlag.IMPERSONATION -> {
                val brands = t.matchedPhrases(IMPERSONATED)
                val ownApp = pkg != null && brands.any { pkg.lowercase().contains(it.replace(" ", "")) }
                if (ownApp) emptyList() else brands
            }
            RedFlag.SUSPICIOUS_LINK -> links.filter { it.suspicious }.flatMap { l -> l.reasons.map { "${l.host}: $it" } }
            RedFlag.TOO_GOOD -> t.matchedPhrases(TOO_GOOD_WORDS)
            RedFlag.THREAT -> t.matchedPhrases(THREAT_WORDS)
            RedFlag.MOVE_CHANNEL -> t.matchedPhrases(MOVE_WORDS)
        }
    }

    /** The rule-based answer to one red-flag question (what the model is blended with). */
    fun rules(text: String, flag: RedFlag, sourcePackage: String? = null): Decision =
        rulesFor(flag, evidence(flag, text, LinkInspector.inspect(text), sourcePackage))

    private fun rulesFor(flag: RedFlag, evidence: List<String>): Decision {
        val r = ScoredRules(YES_NO).add(NO, 1.5f)
        if (evidence.isNotEmpty()) r.add(YES, 1.2f + evidence.size.coerceAtMost(3) * 0.8f)
        return r.toDecision()
    }

    companion object {
        /** The model is blended with the evidence rules so it can't make the shield worse than rules alone. */
        const val RULES_WEIGHT = 1f
        const val YES = "yes"
        const val NO = "no"
        val YES_NO = listOf(YES, NO)

        val URGENCY_WORDS = listOf(
            "urgent", "immediately", "within 24 hours", "within 24h", "within 2 hours", "today only", "act now",
            "final notice", "last chance", "last warning", "expires today", "as soon as possible", "asap",
            "right away", "before it's too late", "will be deleted", "time sensitive",
        )
        val PAYMENT_WORDS = listOf(
            "pay", "payment", "fee", "customs fee", "delivery fee", "redelivery", "outstanding balance",
            "gift card", "itunes card", "google play card", "bitcoin", "crypto", "usdt", "wire transfer",
            "bank transfer", "western union", "send money", "transfer the", "unpaid", "settle",
        )
        val ASK_FOR_CODE = Regex(
            """(send|share|give|tell|forward|reply with|provide|confirm|enter|verify)\b[^.\n]{0,40}\b(code|otp|pin|password|passcode|cvv)\b|(code|otp|pin|password)\b[^.\n]{0,25}\b(you received|we sent you|sent to your phone)\b[^.\n]{0,25}\b(send|share|forward|reply)"""
        )
        val IMPERSONATED = listOf(
            "bank", "qnb", "cbq", "doha bank", "hsbc", "chase", "paypal", "visa", "mastercard", "dhl", "fedex",
            "ups", "usps", "aramex", "qatar post", "royal mail", "post office", "customs", "amazon", "apple",
            "icloud", "microsoft", "netflix", "whatsapp", "google", "ministry", "moi", "metrash", "police",
            "tax", "irs", "hmrc", "government", "ooredoo", "vodafone", "kahramaa", "binance",
        )
        val TOO_GOOD_WORDS = listOf(
            "you have won", "you've won", "winner", "congratulations", "prize", "claim your", "free gift",
            "refund", "reward", "lottery", "jackpot", "selected", "cash prize", "double your", "guaranteed return",
            "investment opportunity", "earn money", "work from home",
        )
        val THREAT_WORDS = listOf(
            "suspended", "suspension", "blocked", "locked", "deactivated", "closed", "legal action", "arrest",
            "warrant", "fine", "penalty", "court", "will be terminated", "unusual activity", "unauthorized",
        )
        val MOVE_WORDS = listOf(
            "whatsapp me", "text me on", "contact us on", "add me on", "telegram", "call this number",
            "call us at", "click the link", "click here", "tap the link", "visit", "log in at", "login at",
        )
    }
}
