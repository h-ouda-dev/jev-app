package app.jev.core.scam

import java.util.Locale

data class LinkFinding(val url: String, val host: String, val reasons: List<String>) {
    val suspicious: Boolean get() = reasons.isNotEmpty()
}

/** Pure, offline URL heuristics. Nothing is fetched. */
object LinkInspector {
    private val URL = Regex(
        """(?i)\b((?:https?://|www\.)[^\s<>"']+|(?:[a-z0-9-]+\.)+(?:com|net|org|info|xyz|top|click|link|live|shop|online|site|icu|vip|buzz|rest|zip|mov|ly|me|co|io|app|qa|ae|sa|uk|us|cc|tk|ml|ga|cf|gq|support|help|services|delivery)(?:/[^\s<>"']*)?)"""
    )

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly", "rb.gy",
        "shorturl.at", "tiny.cc", "s.id", "rebrand.ly", "t.ly", "bit.do", "v.gd", "qr.io",
    )
    private val RISKY_TLDS = setOf(
        "xyz", "top", "click", "link", "live", "shop", "online", "site", "icu", "vip", "buzz", "rest",
        "zip", "mov", "tk", "ml", "ga", "cf", "gq", "cc", "support", "help", "services", "delivery", "info",
    )
    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "gov.uk", "com.qa", "gov.qa", "edu.qa", "org.qa", "net.qa", "com.au", "co.in",
        "com.sa", "gov.sa", "co.ae", "gov.ae", "com.eg", "co.jp", "com.br", "com.tr",
    )

    /** Brand words that scammers put in fake domains, mapped to the brand's real registrable domains. */
    val BRAND_DOMAINS: Map<String, Set<String>> = mapOf(
        "dhl" to setOf("dhl.com", "dhl.de"),
        "fedex" to setOf("fedex.com"),
        "ups" to setOf("ups.com"),
        "usps" to setOf("usps.com"),
        "aramex" to setOf("aramex.com"),
        "qpost" to setOf("qatarpost.qa"),
        "qatarpost" to setOf("qatarpost.qa"),
        "royalmail" to setOf("royalmail.com"),
        "amazon" to setOf("amazon.com", "amazon.co.uk", "amazon.ae", "amazon.sa", "amzn.to"),
        "paypal" to setOf("paypal.com", "paypal.me"),
        "apple" to setOf("apple.com", "icloud.com"),
        "icloud" to setOf("icloud.com", "apple.com"),
        "microsoft" to setOf("microsoft.com", "live.com", "office.com"),
        "netflix" to setOf("netflix.com"),
        "google" to setOf("google.com", "goo.gl", "youtube.com"),
        "whatsapp" to setOf("whatsapp.com", "wa.me"),
        "facebook" to setOf("facebook.com", "fb.com"),
        "instagram" to setOf("instagram.com"),
        "qnb" to setOf("qnb.com"),
        "cbq" to setOf("cbq.qa"),
        "dohabank" to setOf("dohabank.com.qa"),
        "ooredoo" to setOf("ooredoo.qa", "ooredoo.com"),
        "vodafone" to setOf("vodafone.qa", "vodafone.com", "vodafone.co.uk"),
        "hsbc" to setOf("hsbc.com", "hsbc.co.uk"),
        "chase" to setOf("chase.com"),
        "wellsfargo" to setOf("wellsfargo.com"),
        "bankofamerica" to setOf("bankofamerica.com"),
        "visa" to setOf("visa.com"),
        "mastercard" to setOf("mastercard.com"),
        "metrash" to setOf("moi.gov.qa"),
        "irs" to setOf("irs.gov"),
        "hmrc" to setOf("gov.uk"),
        "binance" to setOf("binance.com"),
        "coinbase" to setOf("coinbase.com"),
    )

    fun extract(text: String): List<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?') }.distinct().toList()

    fun inspect(text: String): List<LinkFinding> = extract(text).map(::inspectUrl)

    fun inspectUrl(raw: String): LinkFinding {
        val url = raw.lowercase(Locale.ROOT)
        val withoutScheme = url.removePrefix("https://").removePrefix("http://")
        val hostPort = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringAfterLast('@').substringBefore(':').removePrefix("www.")
        val reasons = mutableListOf<String>()

        if (url.startsWith("http://")) reasons += "Not encrypted (http)"
        if ('@' in hostPort) reasons += "Hides the real address with '@'"
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) reasons += "Uses a raw IP address"
        if (host.split('.').any { it.startsWith("xn--") }) reasons += "Uses look-alike (punycode) letters"
        if (host in SHORTENERS) reasons += "Shortened link hides the destination"
        val tld = host.substringAfterLast('.')
        if (tld in RISKY_TLDS) reasons += "Unusual domain ending .$tld"
        val reg = registrableDomain(host)
        val label = reg.substringBefore('.')
        if (label.count { it == '-' } >= 2 || host.split('.').size >= 5) reasons += "Long or hyphen-heavy address"

        val compactHost = host.replace("-", "").replace(".", "")
        for ((brand, official) in BRAND_DOMAINS) {
            if (brand.length < 3) continue
            if (compactHost.contains(brand) && reg !in official && !official.any { host.endsWith(".$it") || host == it }) {
                reasons += "Uses the name \"$brand\" but isn't ${brand}'s real website"
                break
            }
        }
        return LinkFinding(raw, host, reasons)
    }

    fun registrableDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return host
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL_SUFFIXES) parts.takeLast(3).joinToString(".") else lastTwo
    }
}
