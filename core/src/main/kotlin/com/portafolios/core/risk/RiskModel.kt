package com.portafolios.core.risk

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Asset classes a portfolio target is defined on. */
enum class AssetClass(val label: String) {
    EQUITY("Renta variable (acciones y ETFs)"),
    BONDS("Renta fija (bonos)"),
    CASH("Liquidez y monetario"),
    GOLD("Oro y materias primas"),
    REAL_ESTATE("Inmobiliario (REITs)"),
    CRYPTO("Criptomonedas"),
}

/**
 * Long-term capital market assumptions (annual, USD), in the range published by large managers
 * (Vanguard, BlackRock, J.P. Morgan LTCMA). Volatility and correlations drive the risk numbers;
 * expected returns only feed the probability of loss. They are estimates, not promises.
 */
object Assumptions {
    val volatility = mapOf(
        AssetClass.EQUITY to 0.16, AssetClass.BONDS to 0.06, AssetClass.CASH to 0.005,
        AssetClass.GOLD to 0.15, AssetClass.REAL_ESTATE to 0.20, AssetClass.CRYPTO to 0.65,
    )
    val expectedReturn = mapOf(
        AssetClass.EQUITY to 0.07, AssetClass.BONDS to 0.04, AssetClass.CASH to 0.025,
        AssetClass.GOLD to 0.04, AssetClass.REAL_ESTATE to 0.065, AssetClass.CRYPTO to 0.10,
    )
    private val order = AssetClass.entries
    private val corr = arrayOf(
        //            EQ    BND   CASH  GOLD  REIT  CRYPTO
        doubleArrayOf(1.00, 0.20, 0.00, 0.10, 0.75, 0.35),
        doubleArrayOf(0.20, 1.00, 0.10, 0.30, 0.30, 0.05),
        doubleArrayOf(0.00, 0.10, 1.00, 0.00, 0.00, 0.00),
        doubleArrayOf(0.10, 0.30, 0.00, 1.00, 0.15, 0.10),
        doubleArrayOf(0.75, 0.30, 0.00, 0.15, 1.00, 0.30),
        doubleArrayOf(0.35, 0.05, 0.00, 0.10, 0.30, 1.00),
    )

    fun covariance(a: AssetClass, b: AssetClass): Double =
        volatility.getValue(a) * volatility.getValue(b) * corr[order.indexOf(a)][order.indexOf(b)]
}

/** Probabilistic risk figures of an allocation over one year (normal / log-normal approximation). */
data class RiskMetrics(
    /** Annual volatility (standard deviation), percent. */
    val volatility: Double,
    /** Expected annual return, percent. */
    val expectedReturn: Double,
    /** 95% one-year Value at Risk: a loss this size or bigger happens about 1 year in 20, percent. */
    val valueAtRisk95: Double,
    /** Average loss in that worst 5% of years (Expected Shortfall / CVaR), percent. */
    val expectedShortfall95: Double,
    /** Probability of ending one year with less money than at the start, percent. */
    val probLoss1y: Double,
    /** Probability of ending the horizon with less money than at the start, percent. */
    val probLossHorizon: Double,
    /** Share of total portfolio risk that comes from each class (risk contribution), percent. */
    val riskContribution: Map<AssetClass, Double>,
)

object RiskModel {
    private const val Z95 = 1.6448536
    private const val PHI_Z95 = 0.1031356 // standard normal density at Z95

    /** [weights] in percent (they are normalized, so they need not add up to exactly 100). */
    fun metrics(weights: Map<AssetClass, Double>, horizonYears: Int = 5): RiskMetrics {
        val total = weights.values.sum()
        require(total > 0) { "Sin pesos" }
        val w = AssetClass.entries.associateWith { (weights[it] ?: 0.0) / total }
        val sigmaW = AssetClass.entries.associateWith { a -> AssetClass.entries.sumOf { b -> Assumptions.covariance(a, b) * w.getValue(b) } }
        val variance = AssetClass.entries.sumOf { w.getValue(it) * sigmaW.getValue(it) }
        val vol = sqrt(variance)
        val mu = AssetClass.entries.sumOf { w.getValue(it) * Assumptions.expectedReturn.getValue(it) }
        val contribution = AssetClass.entries.associateWith { if (variance == 0.0) 0.0 else 100 * w.getValue(it) * sigmaW.getValue(it) / variance }
        return RiskMetrics(
            volatility = 100 * vol,
            expectedReturn = 100 * mu,
            valueAtRisk95 = 100 * (Z95 * vol - mu).coerceAtLeast(0.0),
            expectedShortfall95 = 100 * (vol * PHI_Z95 / 0.05 - mu).coerceAtLeast(0.0),
            probLoss1y = 100 * probLoss(mu, vol, 1),
            probLossHorizon = 100 * probLoss(mu, vol, horizonYears.coerceAtLeast(1)),
            riskContribution = contribution,
        )
    }

    /** P(wealth after T years < initial) with log-normal returns. */
    private fun probLoss(mu: Double, vol: Double, years: Int): Double {
        if (vol == 0.0) return if (mu < 0) 1.0 else 0.0
        val logMu = ln(1 + mu) - vol * vol / 2
        val z = -(logMu * years) / (vol * sqrt(years.toDouble()))
        return normalCdf(z)
    }

    internal fun normalCdf(x: Double): Double {
        // Abramowitz-Stegun 7.1.26, error < 1.5e-7.
        val t = 1 / (1 + 0.3275911 * kotlin.math.abs(x) / sqrt(2.0))
        val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x / 2)
        return if (x >= 0) (1 + y) / 2 else (1 - y) / 2
    }
}
