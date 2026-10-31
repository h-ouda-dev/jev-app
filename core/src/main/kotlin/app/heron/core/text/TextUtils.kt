package app.heron.core.text

import java.util.Locale

internal fun String.norm(): String = lowercase(Locale.ROOT)

/** True if any of [phrases] appears in this text as a whole word / phrase. */
internal fun String.containsAnyPhrase(phrases: Collection<String>): Boolean = phrases.any { containsPhrase(it) }

internal fun String.matchedPhrases(phrases: Collection<String>): List<String> = phrases.filter { containsPhrase(it) }

internal fun String.containsPhrase(phrase: String): Boolean {
    val p = Regex.escape(phrase.norm())
    return Regex("(?<![\\p{L}\\p{N}])$p(?![\\p{L}\\p{N}])").containsMatchIn(this.norm())
}

/** Keeps a model prompt short: collapses whitespace and truncates. */
fun compact(text: String, maxChars: Int = 600): String {
    val t = text.replace(Regex("\\s+"), " ").trim()
    return if (t.length <= maxChars) t else t.take(maxChars - 1) + "…"
}
