package app.heron.core.money

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Minimal .xlsx reader for bank statements: the first worksheet as rows of
 * strings (shared and inline strings, numbers as written). Dates come through
 * as Excel day numbers, which [StatementCsv] understands.
 */
object XlsxReader {
    fun firstSheet(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) {
                    files[e.name] = zip.readBytes().decodeToString()
                }
            }
        }
        val sheetName = files.keys.filter { it.startsWith("xl/worksheets/sheet") }
            .minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
            ?: throw IllegalArgumentException("This Excel file has no worksheet")
        val shared = files["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        return rows(files.getValue(sheetName), shared)
    }

    private val SI = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val T = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val V = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val REF = Regex("""\br="([A-Z]+)\d+"""")
    private val TYPE = Regex("""\bt="(\w+)"""")

    private fun sharedStrings(xml: String): List<String> =
        SI.findAll(xml).map { si -> T.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList()

    private fun rows(xml: String, shared: List<String>): List<List<String>> = ROW.findAll(xml).map { row ->
        val cells = sortedMapOf<Int, String>()
        var next = 0
        for (c in CELL.findAll(row.groupValues[1])) {
            val attrs = c.groupValues[1]
            val body = c.groupValues[2]
            val col = REF.find(attrs)?.groupValues?.get(1)?.let(::columnIndex) ?: next
            next = col + 1
            val value = when (TYPE.find(attrs)?.groupValues?.get(1)) {
                "s" -> V.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                "inlineStr" -> T.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                else -> V.find(body)?.groupValues?.get(1)?.let(::unescape).orEmpty()
            }
            cells[col] = value
        }
        val width = (cells.keys.maxOrNull() ?: -1) + 1
        List(width) { cells[it].orEmpty() }
    }.toList()

    private fun columnIndex(letters: String) = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
