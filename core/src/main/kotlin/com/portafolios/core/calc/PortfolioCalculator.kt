package com.portafolios.core.calc

import com.portafolios.core.model.Position
import com.portafolios.core.model.Quote
import com.portafolios.core.model.Transaction
import com.portafolios.core.model.TxType
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private val MC = MathContext.DECIMAL64
private val HUNDRED = BigDecimal(100)

data class Holding(
    val position: Position,
    val quote: Quote?,
) {
    val marketValue: BigDecimal? get() = quote?.let { position.quantity * it.price }
    val unrealizedPnl: BigDecimal? get() = marketValue?.minus(position.costBasis)
    val unrealizedPnlPercent: BigDecimal?
        get() = unrealizedPnl?.takeIf { position.costBasis.signum() != 0 }
            ?.divide(position.costBasis, MC)?.multiply(HUNDRED)
    val dayChange: BigDecimal? get() = quote?.let { position.quantity * it.change }
}

data class PortfolioSummary(
    val holdings: List<Holding>,
    val cash: Map<String, BigDecimal>,
    val marketValue: BigDecimal,
    val costBasis: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val realizedPnl: BigDecimal,
    val dividends: BigDecimal,
    val dayChange: BigDecimal,
    /** Weight of each open holding (by symbol) over invested market value, in percent. */
    val weights: Map<String, BigDecimal>,
) {
    val unrealizedPnlPercent: BigDecimal
        get() = if (costBasis.signum() == 0) BigDecimal.ZERO else unrealizedPnl.divide(costBasis, MC).multiply(HUNDRED)
}

object PortfolioCalculator {

    /** Average-cost positions per instrument. Closed positions are kept for realized P&L and dividends. */
    fun positions(transactions: List<Transaction>): List<Position> {
        data class Acc(var qty: BigDecimal, var avg: BigDecimal, var realized: BigDecimal, var dividends: BigDecimal, var name: String?, var currency: String)

        val acc = linkedMapOf<String, Acc>()
        for (tx in sorted(transactions)) {
            val key = tx.instrumentKey ?: continue
            val a = acc.getOrPut(key) { Acc(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, tx.name, tx.currency) }
            if (a.name == null) a.name = tx.name
            when (tx.type) {
                TxType.BUY -> {
                    val cost = a.qty * a.avg + tx.quantity * tx.price + tx.fee
                    a.qty += tx.quantity
                    a.avg = if (a.qty.signum() == 0) BigDecimal.ZERO else cost.divide(a.qty, MC)
                    a.currency = tx.currency
                }
                TxType.SELL -> {
                    val sold = tx.quantity.min(a.qty.max(BigDecimal.ZERO))
                    a.realized += sold * (tx.price - a.avg) - tx.fee
                    a.qty -= tx.quantity
                    if (a.qty.abs() < BigDecimal("0.000001")) { a.qty = BigDecimal.ZERO; a.avg = BigDecimal.ZERO }
                }
                TxType.SPLIT -> if (tx.quantity.signum() > 0) {
                    a.qty *= tx.quantity
                    a.avg = a.avg.divide(tx.quantity, MC)
                }
                TxType.DIVIDEND -> a.dividends += tx.amount
                TxType.TAX -> a.dividends -= tx.amount
                else -> {}
            }
        }
        return acc.map { (symbol, a) ->
            Position(symbol, a.name, a.currency, a.qty.stripZeros(), a.avg.setScale(6, RoundingMode.HALF_UP), a.realized.setScale(2, RoundingMode.HALF_UP), a.dividends.setScale(2, RoundingMode.HALF_UP))
        }
    }

    /** Cash balance per currency implied by the transactions. */
    fun cash(transactions: List<Transaction>): Map<String, BigDecimal> {
        val cash = sortedMapOf<String, BigDecimal>()
        fun add(cur: String, v: BigDecimal) { cash[cur] = (cash[cur] ?: BigDecimal.ZERO) + v }
        for (tx in transactions) {
            when (tx.type) {
                TxType.BUY -> add(tx.currency, -(tx.amount + tx.fee))
                TxType.SELL -> add(tx.currency, tx.amount - tx.fee)
                TxType.DEPOSIT, TxType.DIVIDEND, TxType.INTEREST -> add(tx.currency, tx.amount)
                TxType.WITHDRAWAL, TxType.FEE, TxType.TAX -> add(tx.currency, -tx.amount)
                TxType.SPLIT -> {}
            }
        }
        return cash
    }

    fun summary(transactions: List<Transaction>, quotes: Map<String, Quote>): PortfolioSummary {
        val positions = positions(transactions)
        val holdings = positions.filter { it.isOpen }.map { Holding(it, quotes[it.symbol]) }
        val priced = holdings.filter { it.marketValue != null }
        val marketValue = priced.fold(BigDecimal.ZERO) { s, h -> s + h.marketValue!! }
        val cost = priced.fold(BigDecimal.ZERO) { s, h -> s + h.position.costBasis }
        val weights = if (marketValue.signum() == 0) emptyMap() else priced.associate {
            it.position.symbol to it.marketValue!!.divide(marketValue, MC).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
        }
        return PortfolioSummary(
            holdings = holdings.sortedByDescending { it.marketValue ?: BigDecimal.ZERO },
            cash = cash(transactions),
            marketValue = marketValue.setScale(2, RoundingMode.HALF_UP),
            costBasis = cost.setScale(2, RoundingMode.HALF_UP),
            unrealizedPnl = (marketValue - cost).setScale(2, RoundingMode.HALF_UP),
            realizedPnl = positions.fold(BigDecimal.ZERO) { s, p -> s + p.realizedPnl },
            dividends = positions.fold(BigDecimal.ZERO) { s, p -> s + p.dividends },
            dayChange = priced.fold(BigDecimal.ZERO) { s, h -> s + (h.dayChange ?: BigDecimal.ZERO) }.setScale(2, RoundingMode.HALF_UP),
            weights = weights,
        )
    }

    /** Same-day order: buys and splits before sells so a day trade never goes negative. */
    private fun sorted(txs: List<Transaction>) = txs.withIndex().sortedWith(
        compareBy({ it.value.date }, { if (it.value.type == TxType.SELL) 1 else 0 }, { it.index }),
    ).map { it.value }

    private fun BigDecimal.stripZeros(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()
}
