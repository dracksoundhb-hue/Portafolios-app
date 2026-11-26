package com.portafolios.core

import com.portafolios.core.analysis.Insight
import com.portafolios.core.analysis.PortfolioAnalyzer
import com.portafolios.core.calc.PortfolioCalculator
import com.portafolios.core.model.Broker
import com.portafolios.core.model.Quote
import com.portafolios.core.model.SecurityProfile
import com.portafolios.core.model.Transaction
import com.portafolios.core.model.TxType
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalculatorTest {
    private fun bd(s: String) = BigDecimal(s)
    private val d = LocalDate.of(2024, 1, 1)
    private fun buy(sym: String, q: String, p: String, fee: String = "0", day: Long = 0) =
        Transaction(d.plusDays(day), TxType.BUY, sym, bd(q), bd(p), bd(fee), bd(q) * bd(p))
    private fun sell(sym: String, q: String, p: String, fee: String = "0", day: Long = 0) =
        Transaction(d.plusDays(day), TxType.SELL, sym, bd(q), bd(p), bd(fee), bd(q) * bd(p))

    @Test fun averageCostAndRealized() {
        val txs = listOf(buy("A", "10", "100", "10"), buy("A", "10", "120", day = 1), sell("A", "5", "130", "5", day = 2))
        val p = PortfolioCalculator.positions(txs).single()
        assertEquals(0, bd("15").compareTo(p.quantity))
        assertEquals(0, bd("110.5").compareTo(p.averageCost))
        assertEquals(0, bd("92.50").compareTo(p.realizedPnl)) // 5*(130-110.5) - 5
    }

    @Test fun sameDaySellAfterBuy() {
        val txs = listOf(sell("A", "5", "11"), buy("A", "5", "10"))
        val p = PortfolioCalculator.positions(txs).single()
        assertEquals(0, BigDecimal.ZERO.compareTo(p.quantity))
        assertEquals(0, bd("5").compareTo(p.realizedPnl))
    }

    @Test fun splitAdjustsQuantityAndCost() {
        val txs = listOf(buy("N", "10", "1000"), Transaction(d.plusDays(5), TxType.SPLIT, "N", quantity = bd("10")))
        val p = PortfolioCalculator.positions(txs).single()
        assertEquals(0, bd("100").compareTo(p.quantity)); assertEquals(0, bd("100").compareTo(p.averageCost))
    }

    @Test fun summaryWeightsAndInsights() {
        val txs = listOf(
            Transaction(d, TxType.DEPOSIT, null, amount = bd("10000")),
            buy("BIG", "50", "100"), buy("B", "10", "100"), buy("C", "10", "100"), buy("LOSER", "10", "100"),
        )
        val quotes = mapOf(
            "BIG" to Quote("BIG", bd("110"), bd("1"), bd("1")), "B" to Quote("B", bd("100"), bd("0"), bd("0")),
            "C" to Quote("C", bd("100"), bd("0"), bd("0")), "LOSER" to Quote("LOSER", bd("70"), bd("-1"), bd("-1")),
        )
        val s = PortfolioCalculator.summary(txs, quotes)
        assertEquals(0, bd("8200").compareTo(s.marketValue))
        assertEquals(0, bd("2000").compareTo(s.cash["USD"]))
        val profiles = mapOf("BIG" to SecurityProfile("BIG", null, "Technology", "US"))
        val insights = PortfolioAnalyzer.insights(s, profiles)
        assertTrue(insights.any { it.kind == Insight.Kind.CONCENTRATION && it.symbol == "BIG" })
        assertTrue(insights.any { it.kind == Insight.Kind.LOSS && it.symbol == "LOSER" })
        assertTrue(insights.any { it.kind == Insight.Kind.SECTOR_GAP })
    }

    @Test fun reimportingSameFileIsDeduplicated() {
        val file = csv("snowball.csv", """
            Event,Date,Symbol,Price,Quantity,Currency,FeeTax
            Buy,2024-01-15,KO,60,10,USD,0
            Buy,2024-01-15,KO,60,10,USD,0
        """)
        val first = preview(file, Broker.SNOWBALL_ANALYTICS)
        assertEquals(2, first.newRows.size) // two identical lots are both kept
        val again = preview(file, Broker.SNOWBALL_ANALYTICS, first.rows.map { it.fingerprint }.toSet())
        assertEquals(0, again.newRows.size); assertEquals(2, again.duplicateCount)
    }
}
