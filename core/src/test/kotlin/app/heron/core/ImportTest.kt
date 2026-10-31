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
        assertEquals("Carrefour City Center", txs[0].merchant)
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

class FxAndPromoTest {
    @Test fun peggedConversionIsExact() {
        assertEquals(91.0, app.heron.core.money.Fx.convert(25.0, "USD", "QAR")!!, 1e-9)
        assertTrue(app.heron.core.money.Fx.isExact("USD", "QAR"))
        assertTrue(!app.heron.core.money.Fx.isExact("EUR", "QAR"))
        assertEquals(null, app.heron.core.money.Fx.convert(10.0, "XYZ", "QAR"))
    }

    @Test fun bankAppMarketingIsNotATransaction() {
        val p = app.heron.core.money.TransactionParser
        listOf(
            "Spend USD 25 with your QNB card to enjoy unique benefits",
            "Use your card abroad and pay USD 25 to enjoy unique benefits",
            "Upgrade to Platinum: exclusive privileges on purchases over QAR 500",
            "QAR 100 reward when you shop with Visa. T&Cs apply",
        ).forEach { assertEquals("Should reject: $it", null, p.parse(it, "com.qnb.mobile", 0)) }
        val real = p.parse("Purchase of USD 25.00 at AMAZON.COM with card ending 4421", "com.qnb.mobile", 0)!!
        assertEquals("USD", real.currency)
        assertEquals("amazon", real.merchantKey)
        assertTrue(p.parse("Transaction of QAR 61.25 at UBER TRIP on card ending 4421", "com.qnb.mobile", 0) != null)
    }
}

class GccMerchantTest {
    private val p = app.heron.core.money.TransactionParser
    private val cat = HeronPipeline().categorizer

    private fun check(sms: String, merchant: String, category: app.heron.core.money.BudgetCategory) {
        val tx = p.parse(sms, "sms:QNB", 0) ?: throw AssertionError("Not parsed: $sms")
        assertEquals("merchant for: $sms", merchant, tx.merchant)
        assertEquals("category for: $sms", category, cat.categorize(tx).first)
    }

    @Test fun labelledLocationFormats() {
        check("Your Credit Card XXXX1234 was used for QAR 45.00 on 31/10/2026 13:45. Location: \"TALABAT DOHA QA\". Available limit QAR 9,955.00", "Talabat", app.heron.core.money.BudgetCategory.DINING)
        check("Purchase transaction\nAmount: QAR 120.50\nLocation: WOQOD STATION 12 DOHA\nCard: ****1234", "Woqod Station", app.heron.core.money.BudgetCategory.TRANSPORT)
        check("QAR 230.75 spent on card ending 1234. Location: \"TALABAT MART\"", "Talabat Mart", app.heron.core.money.BudgetCategory.GROCERIES)
        check("Purchase of QAR 88.00 with Debit Card 1234, Merchant Name: KFC AL SADD, on 01/11/2026", "KFC Al Sadd", app.heron.core.money.BudgetCategory.DINING)
        check("Your card ending 1234 has been used for QAR 310.00 at LULU HYPERMARKET-GHARRAFA on 30-10-2026", "Lulu Hypermarket-gharrafa", app.heron.core.money.BudgetCategory.GROCERIES)
        check("Transaction of QAR 260.00 at OOREDOO QATAR ONLINE on card ending 1234", "Ooredoo Qatar Online", app.heron.core.money.BudgetCategory.BILLS)
        check("Card 1234 used for QAR 1,950.00. Location: \"QATAR AIRWAYS DOHA QA\"", "Qatar Airways", app.heron.core.money.BudgetCategory.TRAVEL)
        check("Purchase of QAR 45.00. Location: \"SNOONU\"", "Snoonu", app.heron.core.money.BudgetCategory.DINING)
        check("Purchase of QAR 19.99. Location: \"SPOTIFY P1234567\"", "Spotify P1234567", app.heron.core.money.BudgetCategory.SUBSCRIPTIONS)
    }

    @Test fun lulExchangeIsATransferNotGroceries() {
        check("Transaction of QAR 2,000.00 at LULU EXCHANGE on card ending 1234", "Lulu Exchange", app.heron.core.money.BudgetCategory.TRANSFERS)
    }
}

class CardStatementTest {
    @Test fun billedColumnsWinAndCardConventionIsDetected() {
        val csv = """
            Date,Description,Transaction Currency,Transaction Amount,Billed Amount,Billed Currency
            30/10/2026,AMAZON.COM AMZN.COM/BILL,USD,25.00,91.00,QAR
            29/10/2026,TALABAT DOHA QA,QAR,64.50,64.50,QAR
            28/10/2026,PAYMENT - THANK YOU,QAR,-2500.00,-2500.00,QAR
            27/10/2026,REFUND NOON.COM,QAR,-120.00,-120.00,QAR
            26/10/2026,WOQOD STATION 12,QAR,150.00,150.00,QAR
        """.trimIndent()
        val r = app.heron.core.money.StatementCsv.parse(csv, "QAR")
        assertEquals(4, r.rows.size) // card payment skipped
        val amazon = r.rows[0]
        assertEquals(91.0, amazon.amount, 1e-9)
        assertEquals("QAR", amazon.currency)
        assertEquals(app.heron.core.money.Direction.DEBIT, amazon.direction)
        assertEquals(app.heron.core.money.Direction.CREDIT, r.rows.first { it.description.startsWith("REFUND") }.direction)
        assertEquals(app.heron.core.money.Direction.DEBIT, r.rows.last().direction)
    }

    @Test fun bankAccountConventionStillWorks() {
        val csv = """
            Date,Description,Amount
            30/10/2026,CARREFOUR,-245.50
            29/10/2026,STARBUCKS,-38.00
            25/10/2026,ACME TRADING,12500.00
        """.trimIndent()
        val r = app.heron.core.money.StatementCsv.parse(csv, "QAR")
        assertEquals(app.heron.core.money.Direction.DEBIT, r.rows[0].direction)
        assertEquals(app.heron.core.money.Direction.CREDIT, r.rows[2].direction)
    }
}
