package com.portafolios.core.analysis

import com.portafolios.core.calc.PortfolioSummary
import com.portafolios.core.model.SecurityProfile
import com.portafolios.core.risk.AssetClass
import com.portafolios.core.risk.AssetClassifier
import com.portafolios.core.risk.PortfolioPlan
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class Insight(val kind: Kind, val title: String, val detail: String, val symbol: String? = null) {
    enum class Kind { CONCENTRATION, SECTOR_GAP, LOSS, CASH, DIVERSIFICATION, OFF_TARGET }
}

data class Slice(val label: String, val percent: BigDecimal)

/** Rule-based review of a portfolio, like the "What to do next" panel of the reference tool. */
object PortfolioAnalyzer {
    private val KEY_SECTORS = listOf("Health Care", "Technology", "Financial Services", "Consumer", "Industrials", "Energy")

    fun allocation(summary: PortfolioSummary, profiles: Map<String, SecurityProfile>, by: (SecurityProfile?) -> String?): List<Slice> {
        val total = summary.holdings.mapNotNull { it.marketValue }.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() == 0) return emptyList()
        return summary.holdings.filter { it.marketValue != null }
            .groupBy { by(profiles[it.position.symbol]) ?: "Sin clasificar" }
            .map { (label, hs) ->
                val v = hs.fold(BigDecimal.ZERO) { s, h -> s + h.marketValue!! }
                Slice(label, v.divide(total, MathContext.DECIMAL64).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP))
            }
            .sortedByDescending { it.percent }
    }

    /** Actual weight of each asset class, percent, counting positive cash as liquidity. Shorts count by size. */
    fun classAllocation(summary: PortfolioSummary, profiles: Map<String, SecurityProfile>): Map<AssetClass, Double> {
        val byClass = AssetClass.entries.associateWith { 0.0 }.toMutableMap()
        summary.holdings.forEach { h ->
            val v = h.marketValue?.abs()?.toDouble() ?: return@forEach
            val c = AssetClassifier.classify(h.position.symbol, profiles[h.position.symbol]?.sector)
            byClass[c] = byClass.getValue(c) + v
        }
        byClass[AssetClass.CASH] = byClass.getValue(AssetClass.CASH) + summary.cash.values.filter { it.signum() > 0 }.sumOf { it.toDouble() }
        val total = byClass.values.sum()
        return if (total == 0.0) emptyMap() else byClass.mapValues { 100 * it.value / total }
    }

    fun insights(summary: PortfolioSummary, profiles: Map<String, SecurityProfile>, plan: PortfolioPlan? = null): List<Insight> {
        val out = mutableListOf<Insight>()
        if (plan != null) {
            val actual = classAllocation(summary, profiles)
            if (actual.isNotEmpty()) AssetClass.entries.forEach { c ->
                val target = plan.targets[c] ?: 0.0
                val now = actual[c] ?: 0.0
                val diff = now - target
                if (kotlin.math.abs(diff) > plan.rebalanceBandPct) {
                    val verb = if (diff > 0) "por encima" else "por debajo"
                    out += Insight(
                        Insight.Kind.OFF_TARGET, "${c.label}: %.1f%% (objetivo %.0f%%)".format(now, target),
                        "Está %.1f puntos %s de tu plan. Tu margen de reequilibrio es ±%.0f puntos.".format(kotlin.math.abs(diff), verb, plan.rebalanceBandPct),
                    )
                }
            }
        }
        val concentrationLimit = BigDecimal(plan?.maxPositionPct ?: 20.0)
        summary.weights.entries.filter { it.value > concentrationLimit }.sortedByDescending { it.value }.forEach { (sym, w) ->
            out += Insight(Insight.Kind.CONCENTRATION, "Revisa $sym (${w.toPlainString()}% de la cartera)",
                if (plan != null) "Supera el máximo por posición de tu plan (${plan.maxPositionPct.toInt()}%)." else "Pesa mucho en la cartera. Comprueba que el tamaño sigue encajando con tu convicción.", sym)
        }
        val top3 = summary.weights.values.sortedDescending().take(3).fold(BigDecimal.ZERO, BigDecimal::add)
        if (summary.weights.size > 3 && top3 > BigDecimal(60)) {
            out += Insight(Insight.Kind.DIVERSIFICATION, "Las 3 mayores posiciones suman ${top3.toPlainString()}%",
                "Una parte grande del resultado depende de pocos valores.")
        }
        val sectors = summary.holdings.mapNotNull { profiles[it.position.symbol]?.sector?.lowercase() }.toSet()
        if (sectors.isNotEmpty()) {
            KEY_SECTORS.filter { key -> sectors.none { it.contains(key.lowercase().substringBefore(' ')) } }.take(2).forEach { s ->
                out += Insight(Insight.Kind.SECTOR_GAP, "Poca o ninguna exposición a $s",
                    "Un sector ausente puede ser un hueco de diversificación. Explora ideas, sin forzar la compra.")
            }
        }
        summary.holdings.mapNotNull { h -> h.unrealizedPnlPercent?.let { h to it } }
            .filter { it.second < BigDecimal(-20) }.sortedBy { it.second }.forEach { (h, pct) ->
                out += Insight(Insight.Kind.LOSS, "Revisa ${h.position.symbol} (${pct.setScale(1, RoundingMode.HALF_UP).toPlainString()}% vs coste)",
                    "Va bastante por debajo de tu precio medio. Revisa la tesis o pon una alerta.", h.position.symbol)
            }
        val cash = summary.cash.values.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
        val total = summary.marketValue + cash
        if (total.signum() > 0 && cash.divide(total, MathContext.DECIMAL64) > BigDecimal("0.25")) {
            out += Insight(Insight.Kind.CASH, "Más del 25% en liquidez", "Es mucho efectivo sin invertir, si no es intencionado.")
        }
        return out
    }
}
