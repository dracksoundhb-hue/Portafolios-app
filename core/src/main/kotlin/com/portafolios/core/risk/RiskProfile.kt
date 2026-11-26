package com.portafolios.core.risk

/**
 * Model portfolios. Each one is a classic strategic allocation (like the conservative / balanced /
 * growth models of large managers) plus a crypto slice sized by *risk budget*: crypto may add at most
 * 5% (low), 10% (medium) or 15% (high) of the portfolio's total risk. With crypto at ~65% volatility
 * that gives about 1%, 3% and 6%, in line with BlackRock (1-2%) and Morgan Stanley (0-4%) guidance.
 */
enum class RiskProfile(
    val label: String,
    val description: String,
    val targets: Map<AssetClass, Int>,
    /** Largest weight of a single position, percent. */
    val maxPositionPct: Int,
    /** Rebalance when a class drifts this many points from its target. */
    val rebalanceBandPct: Int,
    /** Minimum recommended investment horizon, years. */
    val horizonYears: Int,
    /** Largest risk share crypto is allowed to take, percent of total risk. */
    val cryptoRiskBudgetPct: Int,
) {
    LOW(
        "Riesgo leve", "Prioriza conservar el capital. Caídas pequeñas y recuperación rápida.",
        mapOf(AssetClass.EQUITY to 25, AssetClass.BONDS to 54, AssetClass.CASH to 12, AssetClass.GOLD to 6, AssetClass.REAL_ESTATE to 2, AssetClass.CRYPTO to 1),
        maxPositionPct = 5, rebalanceBandPct = 3, horizonYears = 3, cryptoRiskBudgetPct = 5,
    ),
    MEDIUM(
        "Riesgo medio", "Equilibrio entre crecimiento y estabilidad.",
        mapOf(AssetClass.EQUITY to 48, AssetClass.BONDS to 31, AssetClass.CASH to 6, AssetClass.GOLD to 6, AssetClass.REAL_ESTATE to 6, AssetClass.CRYPTO to 3),
        maxPositionPct = 8, rebalanceBandPct = 5, horizonYears = 5, cryptoRiskBudgetPct = 10,
    ),
    HIGH(
        "Riesgo alto", "Busca crecimiento a largo plazo y acepta caídas fuertes por el camino.",
        mapOf(AssetClass.EQUITY to 70, AssetClass.BONDS to 9, AssetClass.CASH to 2, AssetClass.GOLD to 6, AssetClass.REAL_ESTATE to 7, AssetClass.CRYPTO to 6),
        maxPositionPct = 12, rebalanceBandPct = 7, horizonYears = 7, cryptoRiskBudgetPct = 15,
    ),
    CUSTOM(
        "Personalizado", "Tú decides el reparto y los límites. La app te dice a qué riesgo equivale.",
        MEDIUM.targets, maxPositionPct = 8, rebalanceBandPct = 5, horizonYears = 5, cryptoRiskBudgetPct = 10,
    );

    companion object {
        /** Which preset a custom allocation is closest to, by volatility. */
        fun equivalentTo(volatility: Double): RiskProfile {
            val presets = listOf(LOW, MEDIUM, HIGH)
            return presets.minBy { kotlin.math.abs(RiskModel.metrics(it.targets.mapValues { e -> e.value.toDouble() }).volatility - volatility) }
        }
    }
}

/** What the user saved for a portfolio. */
data class PortfolioPlan(
    val profile: RiskProfile,
    val targets: Map<AssetClass, Double>,
    val maxPositionPct: Double,
    val rebalanceBandPct: Double,
    val horizonYears: Int,
) {
    fun metrics() = RiskModel.metrics(targets, horizonYears)

    companion object {
        fun recommended(profile: RiskProfile) = PortfolioPlan(
            profile, profile.targets.mapValues { it.value.toDouble() }, profile.maxPositionPct.toDouble(),
            profile.rebalanceBandPct.toDouble(), profile.horizonYears,
        )
    }
}
