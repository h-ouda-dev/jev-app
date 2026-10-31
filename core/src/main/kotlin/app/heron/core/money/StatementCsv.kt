package app.heron.core.money

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** A row from a bank statement, before categorisation. */
data class StatementRow(val date: LocalDate, val description: String, val amount: Double, val direction: Direction, val currency: String)

data class StatementParseResult(val rows: List<StatementRow>, val skipped: Int, val columns: String)

/**
 * Reads the CSV statements banks let you download (or send by email).
 * Column layouts vary a lot, so the header is matched by meaning:
 * date, description, and either one signed amount or separate debit/credit
 * columns, with an optional type (DR/CR) and currency column.
 */
object StatementCsv {
    // Order = priority. Card statements bill in your currency: prefer the billed date/amount/currency.
    private val DATE_HEADERS = listOf("billed date", "billing date", "bill date", "posting date", "post date", "transaction date", "booking date", "value date", "date")
    private val DESC_HEADERS = listOf(
        "description", "transaction description", "merchant name", "transaction details", "details", "narration", "narrative",
        "merchant", "payee", "particulars", "memo", "remarks", "reference", "name",
    )
    private val AMOUNT_HEADERS = listOf("billed amount", "billing amount", "bill amount", "amount (qar)", "amount", "transaction amount", "value", "amount (usd)")
    private val DEBIT_HEADERS = listOf("debit", "withdrawal", "withdrawals", "money out", "paid out", "debit amount", "dr")
    private val CREDIT_HEADERS = listOf("credit", "deposit", "deposits", "money in", "paid in", "credit amount", "cr")
    private val TYPE_HEADERS = listOf("type", "transaction type", "dr/cr", "debit/credit", "cr/dr")
    private val CURRENCY_HEADERS = listOf("billed currency", "billing currency", "bill currency", "currency", "ccy", "curr", "transaction currency")

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "MM/dd/yyyy", "M/d/yyyy",
        "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "d-MMM-yyyy", "dd MMM yy", "dd/MM/yy", "MMM d, yyyy", "yyyyMMdd",
    )

    /**
     * Reads a statement file as picked by the user: CSV/TSV in any common
     * encoding (UTF-8, UTF-16 as saved by Excel, Windows-1252) or Excel .xlsx.
     */
    fun parseBytes(bytes: ByteArray, defaultCurrency: String): StatementParseResult {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return parseTable(XlsxReader.firstSheet(bytes), defaultCurrency)
        }
        if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xD0 && (bytes[1].toInt() and 0xFF) == 0xCF) {
            throw IllegalArgumentException("This is an old Excel (.xls) file. Open it and save it as .xlsx or CSV, then import that.")
        }
        return parse(decode(bytes), defaultCurrency)
    }

    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            // UTF-16 without a BOM: every other byte is zero for ASCII text.
            bytes.size > 8 && bytes.take(400).count { it.toInt() == 0 } > minOf(bytes.size, 400) / 4 ->
                if (bytes[1].toInt() == 0) String(bytes, Charsets.UTF_16LE) else String(bytes, Charsets.UTF_16BE)
            else -> {
                val utf8 = String(bytes, Charsets.UTF_8)
                if ('\uFFFD' in utf8) String(bytes, charset("windows-1252")) else utf8
            }
        }
    }

    fun parse(text: String, defaultCurrency: String): StatementParseResult {
        val clean = text.removePrefix("\uFEFF")
        val firstLines = clean.lineSequence().filter { it.isNotBlank() }.take(8).toList()
        if (firstLines.isEmpty()) return StatementParseResult(emptyList(), 0, "empty file")
        val delimiter = listOf(',', ';', '\t', '|').maxBy { d -> firstLines.sumOf { splitCsv(it, d).size } }
        return parseTable(tokenize(clean, delimiter), defaultCurrency)
    }

    fun parseTable(rawTable: List<List<String>>, defaultCurrency: String): StatementParseResult {
        val table = rawTable.filter { row -> row.any { it.isNotBlank() } }
        if (table.isEmpty()) return StatementParseResult(emptyList(), 0, "empty file")

        // The header is the first row that names a date column and an amount-ish column (some banks add a preamble).
        val headerIndex = table.take(40).indexOfFirst { row ->
            val h = row.map { it.headerNorm() }
            findCol(h, DATE_HEADERS) != null && (findCol(h, AMOUNT_HEADERS) != null || findCol(h, DEBIT_HEADERS) != null || findCol(h, CREDIT_HEADERS) != null)
        }
        require(headerIndex >= 0) {
            "Couldn't find the date and amount columns. The file starts with: " +
                table.first().joinToString(" | ").take(140)
        }
        val header = table[headerIndex].map { it.headerNorm() }
        val used = mutableSetOf<Int>()
        fun col(names: List<String>) = findCol(header, names, used)?.also { used += it }
        val amountCol = col(AMOUNT_HEADERS)
        val debitCol = col(DEBIT_HEADERS)
        val creditCol = col(CREDIT_HEADERS)
        val currencyCol = col(CURRENCY_HEADERS)
        val dateCol = col(DATE_HEADERS)!!
        val descCol = col(DESC_HEADERS)
        val typeCol = col(TYPE_HEADERS)
        require(amountCol != null || debitCol != null || creditCol != null) { "No amount column" }
        require(descCol != null) { "Couldn't find a description column. Columns: " + table[headerIndex].joinToString(", ") }

        val dataRows = table.drop(headerIndex + 1)
        // Bank accounts write spending as negative; card statements write it as positive and payments as
        // negative. Most rows are spending either way, so the majority sign tells us which convention this is.
        val signs = amountCol?.let { c -> dataRows.mapNotNull { r -> r.getOrNull(c)?.let(::parseAmount)?.takeIf { it != 0.0 } } }.orEmpty()
        val spendingIsNegative = signs.count { it < 0 } > signs.count { it > 0 }
        val dateFormat = pickDateFormat(dataRows.mapNotNull { it.getOrNull(dateCol) })
        val out = mutableListOf<StatementRow>()
        var skipped = 0
        for (row in dataRows) {
            val date = row.getOrNull(dateCol)?.let { parseDate(it, dateFormat) }
            val desc = descCol?.let { row.getOrNull(it) }?.trim().orEmpty()
            if (date == null || desc.isBlank()) { skipped++; continue }
            // Paying off the card isn't income or spending: the purchases are already listed.
            if (CARD_PAYMENT.containsMatchIn(desc)) { skipped++; continue }
            val currency = currencyCol?.let { row.getOrNull(it) }?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: defaultCurrency
            val type = typeCol?.let { row.getOrNull(it) }?.norm()
            val debit = debitCol?.let { row.getOrNull(it) }?.let(::parseAmount)
            val credit = creditCol?.let { row.getOrNull(it) }?.let(::parseAmount)
            val amount = amountCol?.let { row.getOrNull(it) }?.let(::parseAmount)
            val (value, direction) = when {
                debit != null && debit != 0.0 -> kotlin.math.abs(debit) to Direction.DEBIT
                credit != null && credit != 0.0 -> kotlin.math.abs(credit) to Direction.CREDIT
                amount != null && amount != 0.0 -> {
                    val dir = when {
                        type != null && (type.startsWith("cr") || type.startsWith("credit") || type.startsWith("deposit")) -> Direction.CREDIT
                        type != null && (type.startsWith("dr") || type.startsWith("debit") || type.startsWith("with")) -> Direction.DEBIT
                        CREDIT_WORDS.any { desc.lowercase().contains(it) } -> Direction.CREDIT
                        (amount < 0) == spendingIsNegative -> Direction.DEBIT
                        else -> Direction.CREDIT
                    }
                    kotlin.math.abs(amount) to dir
                }
                else -> { skipped++; continue }
            }
            out += StatementRow(date, desc, value, direction, currency)
        }
        val cols = listOfNotNull(
            "date: " + table[headerIndex][dateCol], "description: " + table[headerIndex][descCol],
            amountCol?.let { "amount: " + table[headerIndex][it] }, debitCol?.let { "debit: " + table[headerIndex][it] },
            creditCol?.let { "credit: " + table[headerIndex][it] }, currencyCol?.let { "currency: " + table[headerIndex][it] },
        ).joinToString(" · ")
        return StatementParseResult(out, skipped, cols)
    }

    private val CREDIT_WORDS = listOf("salary", "refund", "deposit", "transfer from", "credit interest", "reversal")
    private val CARD_PAYMENT = Regex("""(?i)(payment\s*[-–]?\s*thank\s*you|thank\s*you\s*for\s*your\s*payment|payment\s+received|autopay|card\s+payment|cc\s+payment|credit\s+card\s+payment)""")

    fun parseAmount(raw: String): Double? {
        var s = raw.trim()
        if (s.isEmpty() || s == "-") return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        val upper = s.uppercase()
        if (upper.endsWith("DR")) { negative = true; s = s.dropLast(2) } else if (upper.endsWith("CR")) s = s.dropLast(2)
        s = s.replace(Regex("[^0-9.,\\-]"), "")
        if (s.startsWith("-")) { negative = !negative; s = s.drop(1) }
        // 1.234,56 (European) vs 1,234.56
        s = if (s.contains(',') && s.contains('.')) {
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        } else if (s.count { it == ',' } == 1 && s.substringAfter(',').length == 2) s.replace(',', '.') else s.replace(",", "")
        val v = s.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    private fun pickDateFormat(samples: List<String>): DateTimeFormatter? {
        val clean = samples.map { it.trim().take(11).trim() }.filter { it.isNotEmpty() }.take(50)
        // Prefer the pattern that parses the most samples; day-first wins ties (most non-US banks).
        return DATE_PATTERNS.map { p -> formatter(p) }
            .maxByOrNull { f -> clean.count { s -> runCatching { LocalDate.parse(s, f) }.isSuccess } }
            ?.takeIf { f -> clean.any { s -> runCatching { LocalDate.parse(s, f) }.isSuccess } }
    }

    private fun parseDate(raw: String, preferred: DateTimeFormatter?): LocalDate? {
        val s = raw.trim()
        // Excel stores dates as day numbers counted from 1899-12-30.
        s.toDoubleOrNull()?.takeIf { it in 20_000.0..80_000.0 }?.let { return LocalDate.of(1899, 12, 30).plusDays(it.toLong()) }
        preferred?.let { f -> runCatching { return LocalDate.parse(s.take(11).trim(), f) } }
        for (p in DATE_PATTERNS) runCatching { return LocalDate.parse(s, formatter(p)) }
        runCatching { return LocalDateTime.parse(s).toLocalDate() }
        return null
    }

    private fun formatter(p: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)

    private fun String.norm() = trim().trim('"').lowercase(Locale.ROOT)

    /** "Billed Amount (QAR) " / "TXN. DATE" -> "billed amount (qar)" / "txn date" */
    private fun String.headerNorm() = replace('\u00A0', ' ').trim().trim('"', '\'', '\uFEFF').lowercase(Locale.ROOT)
        .replace(Regex("""[._]"""), " ").replace(Regex("""\s+"""), " ").trim()

    private val SYNONYMS = mapOf("txn" to "transaction", "trans" to "transaction", "trx" to "transaction", "amt" to "amount", "desc" to "description", "curr" to "currency")

    private fun String.expand() = split(' ').joinToString(" ") { SYNONYMS[it] ?: it }

    /** Exact header match first (in priority order), then a header that contains the name as whole words. */
    private fun findCol(header: List<String>, names: List<String>, exclude: Set<Int> = emptySet()): Int? {
        val h = header.map { it.expand() }
        for (n in names) h.indexOfFirst { it == n }.takeIf { it >= 0 && it !in exclude }?.let { return it }
        for (n in names) {
            if (n.length <= 2) continue
            val re = Regex("""(^|[^a-z])""" + Regex.escape(n) + """([^a-z]|$)""")
            h.indices.firstOrNull { it !in exclude && re.containsMatchIn(h[it]) }?.let { return it }
        }
        return null
    }

    /** Full CSV tokenizer: quoted fields may contain the delimiter, quotes ("") and line breaks. */
    fun tokenize(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row += sb.toString().trim(); sb.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += sb.toString().trim(); sb.clear()
                    rows += row; row = mutableListOf()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row += sb.toString().trim(); rows += row }
        return rows
    }

    /** RFC-4180-ish split: quotes, escaped quotes, and the chosen delimiter. */
    fun splitCsv(line: String, delimiter: Char = ','): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    fun toEpochMillis(d: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}
