package com.portafolios.core.analysis

import com.portafolios.core.calc.PortfolioSummary
import com.portafolios.core.model.SecurityProfile
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class Insight(val kind: Kind, val title: String, val detail: String, val symbol: String? = null) {
    enum class Kind { CONCENTRATION, SECTOR_GAP, LOSS, CASH, DIVERSIFICATION }
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

    fun insights(summary: PortfolioSummary, profiles: Map<String, SecurityProfile>): List<Insight> {
        val out = mutableListOf<Insight>()
        summary.weights.entries.filter { it.value > BigDecimal(20) }.sortedByDescending { it.value }.forEach { (sym, w) ->
            out += Insight(Insight.Kind.CONCENTRATION, "Revisa $sym (${w.toPlainString()}% de la cartera)",
                "Pesa mucho en la cartera. Comprueba que el tamaño sigue encajando con tu convicción.", sym)
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
