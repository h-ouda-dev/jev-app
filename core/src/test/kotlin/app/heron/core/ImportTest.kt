package app.heron.core

import app.heron.core.money.BudgetCategory
import app.heron.core.money.Direction
import app.heron.core.money.HistoryImporter
import app.heron.core.money.RawMessage
import app.heron.core.money.StatementCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ImportTest {
    private val pipeline = HeronPipeline()
    private val importer = HistoryImporter(pipeline.categorizer)
    private val day = 86_400_000L

    @Test fun smsHistoryKeepsOnlyTransactionsAndDedupes() {
        val msgs = listOf(
            RawMessage("QNB", "Your card ending 4421 was used for QAR 245.50 at CARREFOUR CITY CENTER", 10 * day),
            RawMessage("QNB", "Your card ending 4421 was used for QAR 245.50 at CARREFOUR CITY CENTER", 10 * day + 60_000), // duplicate
            RawMessage("QNB", "OTP for your purchase of QAR 300 at NOON is 448812. Do not share.", 11 * day),
            RawMessage("+97455501234", "See you at 8!", 12 * day),
            RawMessage("CBQ", "QAR 12,500.00 has been credited to your account as SALARY", 13 * day),
            RawMessage("QNB", "QAR 38.00 spent at STARBUCKS VILLAGGIO with card ending 4421", 14 * day),
            RawMessage("Ooredoo", "Get 20% off up to QAR 50 on your next recharge!", 15 * day),
        )
        val parsed = importer.fromMessages(msgs)
        assertEquals(4, parsed.size)
        val (imported, dups) = importer.process(parsed, emptyList(), emptyMap(), isDuplicate = { false })
        assertEquals(1, dups)
        assertEquals(3, imported.size)
        assertEquals(BudgetCategory.GROCERIES, imported.first { it.tx.merchantKey.contains("carrefour") }.category)
        assertEquals(BudgetCategory.INCOME, imported.first { it.tx.direction == Direction.CREDIT }.category)
        assertEquals(BudgetCategory.DINING, imported.first { it.tx.merchantKey.contains("starbucks") }.category)
    }

    @Test fun categorisesEachMerchantOnce() {
        val msgs = (1..20).map { RawMessage("QNB", "QAR ${20 + it}.00 spent at KARWA TAXI with card ending 4421", it * day) }
        var calls = 0
        val (imported, _) = importer.process(importer.fromMessages(msgs), emptyList(), emptyMap(), { false }) { _, total -> calls++; assertEquals(1, total) }
        assertEquals(20, imported.size)
        assertEquals(1, calls)
        assertTrue(imported.all { it.category == BudgetCategory.TRANSPORT })
    }

    @Test fun statementWithSignedAmounts() {
        val csv = """
            Account statement,,,
            Generated 01/11/2026,,,
            Date,Description,Amount,Currency
            28/10/2026,POS PURCHASE CARREFOUR CITY CENTER DOHA QA 4421,-245.50,QAR
            27/10/2026,"STARBUCKS, VILLAGGIO",-38.00,QAR
            25/10/2026,SALARY ACME TRADING,"12,500.00",QAR
            24/10/2026,NETFLIX.COM,-45.00,QAR
        """.trimIndent()
        val r = StatementCsv.parse(csv, "QAR")
        assertEquals(4, r.rows.size)
        assertEquals(LocalDate.of(2026, 10, 28), r.rows[0].date)
        assertEquals(Direction.DEBIT, r.rows[0].direction)
        assertEquals(245.50, r.rows[0].amount, 1e-9)
        assertEquals(Direction.CREDIT, r.rows[2].direction)
        assertEquals(12500.0, r.rows[2].amount, 1e-9)
        val txs = importer.fromStatement(r.rows, "test.csv")
        assertEquals("Carrefour City Center Doha", txs[0].merchant)
        val (imported, _) = importer.process(txs, emptyList(), emptyMap(), { false })
        assertEquals(BudgetCategory.SUBSCRIPTIONS, imported.first { it.tx.merchantKey == "netflix" }.category)
    }

    @Test fun statementWithDebitCreditColumnsAndUsDates() {
        val csv = """
            Transaction Date;Details;Debit;Credit
            10/28/2026;UBER TRIP;61,25;
            10/29/2026;REFUND AMAZON;;19,99
            10/30/2026;WELLCARE PHARMACY;66,00;
        """.trimIndent()
        val r = StatementCsv.parse(csv, "USD")
        assertEquals(3, r.rows.size)
        assertEquals(LocalDate.of(2026, 10, 28), r.rows[0].date)
        assertEquals(61.25, r.rows[0].amount, 1e-9)
        assertEquals(Direction.CREDIT, r.rows[1].direction)
        assertEquals("USD", r.rows[2].currency)
    }

    @Test fun amountFormats() {
        assertEquals(-1234.56, StatementCsv.parseAmount("(1,234.56)")!!, 1e-9)
        assertEquals(-1234.56, StatementCsv.parseAmount("1.234,56 DR")!!, 1e-9)
        assertEquals(99.0, StatementCsv.parseAmount("QAR 99.00")!!, 1e-9)
        assertEquals(-5.0, StatementCsv.parseAmount("-5")!!, 1e-9)
    }
}
