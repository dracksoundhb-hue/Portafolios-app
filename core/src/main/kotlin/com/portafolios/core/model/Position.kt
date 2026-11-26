package com.portafolios.core.model

import java.math.BigDecimal

data class Position(
    val symbol: String,
    val name: String?,
    val currency: String,
    val quantity: BigDecimal,
    /** Average cost per unit, fees included. */
    val averageCost: BigDecimal,
    val realizedPnl: BigDecimal,
    val dividends: BigDecimal,
) {
    /** Negative for short positions, like [quantity]. */
    val costBasis: BigDecimal get() = quantity * averageCost
    val isOpen: Boolean get() = quantity.signum() != 0
    val isShort: Boolean get() = quantity.signum() < 0
}

data class Quote(
    val symbol: String, val price: BigDecimal, val change: BigDecimal, val changePercent: BigDecimal,
    /** When this price was traded or published, epoch millis. */
    val timeMillis: Long? = null,
)

data class SecurityProfile(val symbol: String, val name: String?, val sector: String?, val country: String?)
