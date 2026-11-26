package com.portafolios.core

import com.portafolios.core.risk.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskTest {
    private fun m(p: RiskProfile) = PortfolioPlan.recommended(p).metrics()

    @Test fun presetsAddUpTo100() {
        RiskProfile.entries.forEach { assertEquals(100, it.targets.values.sum(), it.name) }
    }

    @Test fun riskGrowsWithProfile() {
        val (l, md, h) = listOf(RiskProfile.LOW, RiskProfile.MEDIUM, RiskProfile.HIGH).map(::m)
        assertTrue(l.volatility < md.volatility && md.volatility < h.volatility)
        assertTrue(l.volatility in 5.5..7.5, "leve ${l.volatility}")
        assertTrue(md.volatility in 9.0..11.5, "medio ${md.volatility}")
        assertTrue(h.volatility in 13.0..16.0, "alto ${h.volatility}")
        assertTrue(l.expectedShortfall95 < md.expectedShortfall95 && md.expectedShortfall95 < h.expectedShortfall95)
    }

    @Test fun cryptoStaysWithinRiskBudget() {
        listOf(RiskProfile.LOW, RiskProfile.MEDIUM, RiskProfile.HIGH).forEach { p ->
            val rc = m(p).riskContribution.getValue(AssetClass.CRYPTO)
            assertTrue(rc <= p.cryptoRiskBudgetPct + 1.0, "${p.name} cripto aporta $rc% del riesgo")
        }
        // Doubling crypto in the low profile breaks its budget.
        val heavy = RiskProfile.LOW.targets.mapValues { it.value.toDouble() }.toMutableMap().apply { put(AssetClass.CRYPTO, 4.0); put(AssetClass.BONDS, 51.0) }
        assertTrue(RiskModel.metrics(heavy).riskContribution.getValue(AssetClass.CRYPTO) > RiskProfile.LOW.cryptoRiskBudgetPct)
    }

    @Test fun riskContributionsAddUp() {
        assertEquals(100.0, m(RiskProfile.HIGH).riskContribution.values.sum(), 0.01)
    }

    @Test fun normalCdf() {
        assertEquals(0.5, RiskModel.normalCdf(0.0), 1e-6)
        assertEquals(0.95, RiskModel.normalCdf(1.6448536), 1e-4)
    }

    @Test fun equivalentProfile() {
        assertEquals(RiskProfile.HIGH, RiskProfile.equivalentTo(m(RiskProfile.HIGH).volatility))
        val allEquity = RiskModel.metrics(mapOf(AssetClass.EQUITY to 100.0))
        assertEquals(RiskProfile.HIGH, RiskProfile.equivalentTo(allEquity.volatility))
    }

    @Test fun classifier() {
        assertEquals(AssetClass.CRYPTO, AssetClassifier.classify("ETH/USD"))
        assertEquals(AssetClass.CASH, AssetClassifier.classify("SGOV"))
        assertEquals(AssetClass.BONDS, AssetClassifier.classify("TLT"))
        assertEquals(AssetClass.REAL_ESTATE, AssetClassifier.classify("VICI"))
        assertEquals(AssetClass.EQUITY, AssetClassifier.classify("NKE", "Textiles"))
    }
}
