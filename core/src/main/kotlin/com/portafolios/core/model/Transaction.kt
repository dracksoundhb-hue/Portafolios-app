package com.portafolios.core.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

enum class TxType { BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, FEE, TAX, INTEREST, SPLIT }

/**
 * A normalized transaction read from a broker statement.
 *
 * Conventions (all values positive, the [type] gives the direction):
 * - [quantity]: number of shares/units. For SPLIT it is the ratio (2 for a 2:1 split).
 * - [price]: price per unit in [currency].
 * - [fee]: commissions and fees in [currency].
 * - [amount]: cash amount for cash-only movements (dividend, deposit, fee, tax, interest).
 *   For BUY/SELL it is quantity * price when the broker does not give a total.
 */
data class Transaction(
    val date: LocalDate,
    val type: TxType,
    val symbol: String?,
    val quantity: BigDecimal = BigDecimal.ZERO,
    val price: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
    val currency: String = "USD",
    val isin: String? = null,
    val name: String? = null,
    /** Broker's own id for the row when it has one (order id, transaction id...). */
    val brokerRef: String? = null,
) {
    /** Key used to group transactions of the same instrument. */
    val instrumentKey: String? get() = symbol?.uppercase() ?: isin?.uppercase()

    /**
     * Stable identity used to skip rows already imported. [occurrence] separates rows that are
     * genuinely identical inside one file (two equal buys on the same day), so re-importing the same
     * period maps every row onto the same fingerprint again.
     */
    fun fingerprint(occurrence: Int = 0): String {
        val raw = listOf(
            date.toString(), type.name, instrumentKey.orEmpty(),
            quantity.stripTrailingZerosSafe(), price.stripTrailingZerosSafe(),
            fee.stripTrailingZerosSafe(), amount.stripTrailingZerosSafe(),
            currency.uppercase(), brokerRef.orEmpty(), occurrence.toString(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun BigDecimal.stripTrailingZerosSafe(): String =
    if (signum() == 0) "0" else stripTrailingZeros().toPlainString()
