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
    private val DATE_HEADERS = listOf("transaction date", "posting date", "booking date", "value date", "date")
    private val DESC_HEADERS = listOf("description", "transaction details", "details", "narration", "narrative", "merchant", "payee", "particulars", "memo", "reference", "name")
    private val AMOUNT_HEADERS = listOf("amount", "transaction amount", "value", "amount (qar)", "amount (usd)")
    private val DEBIT_HEADERS = listOf("debit", "withdrawal", "withdrawals", "money out", "paid out", "debit amount", "dr")
    private val CREDIT_HEADERS = listOf("credit", "deposit", "deposits", "money in", "paid in", "credit amount", "cr")
    private val TYPE_HEADERS = listOf("type", "transaction type", "dr/cr", "debit/credit", "cr/dr")
    private val CURRENCY_HEADERS = listOf("currency", "ccy", "curr")

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "MM/dd/yyyy", "M/d/yyyy",
        "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "d-MMM-yyyy", "dd MMM yy", "dd/MM/yy", "MMM d, yyyy", "yyyyMMdd",
    )

    fun parse(text: String, defaultCurrency: String): StatementParseResult {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return StatementParseResult(emptyList(), 0, "empty file")
        val delimiter = listOf(',', ';', '\t', '|').maxBy { d -> lines.take(5).sumOf { splitCsv(it, d).size } }
        val table = lines.map { splitCsv(it, delimiter) }

        // The header is the first row that names a date column and an amount-ish column (some banks add a preamble).
        val headerIndex = table.indexOfFirst { row ->
            val h = row.map { it.norm() }
            h.any { it in DATE_HEADERS } && h.any { it in AMOUNT_HEADERS || it in DEBIT_HEADERS || it in CREDIT_HEADERS }
        }
        require(headerIndex >= 0) { "Couldn't find a header row with a date and an amount column" }
        val header = table[headerIndex].map { it.norm() }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val dateCol = col(DATE_HEADERS)!!
        val descCol = col(DESC_HEADERS)
        val amountCol = col(AMOUNT_HEADERS)
        val debitCol = col(DEBIT_HEADERS)
        val creditCol = col(CREDIT_HEADERS)
        val typeCol = col(TYPE_HEADERS)
        val currencyCol = col(CURRENCY_HEADERS)
        require(amountCol != null || debitCol != null || creditCol != null) { "No amount column" }

        val dataRows = table.drop(headerIndex + 1)
        val dateFormat = pickDateFormat(dataRows.mapNotNull { it.getOrNull(dateCol) })
        val out = mutableListOf<StatementRow>()
        var skipped = 0
        for (row in dataRows) {
            val date = row.getOrNull(dateCol)?.let { parseDate(it, dateFormat) }
            val desc = descCol?.let { row.getOrNull(it) }?.trim().orEmpty()
            if (date == null || desc.isBlank()) { skipped++; continue }
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
                        amount < 0 -> Direction.DEBIT
                        CREDIT_WORDS.any { desc.lowercase().contains(it) } -> Direction.CREDIT
                        // Statements that sign their amounts use '-' for money out; all-positive files list spending.
                        else -> if (signedAmounts(dataRows, amountCol)) Direction.CREDIT else Direction.DEBIT
                    }
                    kotlin.math.abs(amount) to dir
                }
                else -> { skipped++; continue }
            }
            out += StatementRow(date, desc, value, direction, currency)
        }
        val cols = listOfNotNull("date", descCol?.let { "description" }, amountCol?.let { "amount" }, debitCol?.let { "debit" }, creditCol?.let { "credit" }).joinToString()
        return StatementParseResult(out, skipped, cols)
    }

    private val CREDIT_WORDS = listOf("salary", "refund", "deposit", "transfer from", "credit interest", "reversal")

    private fun signedAmounts(rows: List<List<String>>, col: Int?): Boolean {
        col ?: return false
        return rows.any { it.getOrNull(col)?.let { v -> parseAmount(v)?.let { a -> a < 0 } } == true }
    }

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
        preferred?.let { f -> runCatching { return LocalDate.parse(s.take(11).trim(), f) } }
        for (p in DATE_PATTERNS) runCatching { return LocalDate.parse(s, formatter(p)) }
        runCatching { return LocalDateTime.parse(s).toLocalDate() }
        return null
    }

    private fun formatter(p: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)

    private fun String.norm() = trim().trim('"').lowercase(Locale.ROOT)

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
