package com.portafolios.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.portafolios.core.model.Transaction
import com.portafolios.core.model.TxType
import java.math.BigDecimal
import java.time.LocalDate

@Entity(tableName = "portfolios")
data class PortfolioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val broker: String,
    val baseCurrency: String = "EUR",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Amounts are stored as decimal strings so no precision is lost. */
@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(entity = PortfolioEntity::class, parentColumns = ["id"], childColumns = ["portfolioId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["portfolioId", "fingerprint"], unique = true), Index("importId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portfolioId: Long,
    val date: String,
    val type: String,
    val symbol: String?,
    val isin: String?,
    val name: String?,
    val quantity: String,
    val price: String,
    val fee: String,
    val amount: String,
    val currency: String,
    val brokerRef: String?,
    val fingerprint: String,
    val importId: Long?,
) {
    fun toModel() = Transaction(
        date = LocalDate.parse(date), type = TxType.valueOf(type), symbol = symbol, quantity = BigDecimal(quantity),
        price = BigDecimal(price), fee = BigDecimal(fee), amount = BigDecimal(amount), currency = currency,
        isin = isin, name = name, brokerRef = brokerRef,
    )

    companion object {
        fun from(portfolioId: Long, tx: Transaction, fingerprint: String, importId: Long?) = TransactionEntity(
            portfolioId = portfolioId, date = tx.date.toString(), type = tx.type.name, symbol = tx.symbol, isin = tx.isin,
            name = tx.name, quantity = tx.quantity.toPlainString(), price = tx.price.toPlainString(), fee = tx.fee.toPlainString(),
            amount = tx.amount.toPlainString(), currency = tx.currency, brokerRef = tx.brokerRef, fingerprint = fingerprint, importId = importId,
        )
    }
}

@Entity(
    tableName = "imports",
    foreignKeys = [ForeignKey(entity = PortfolioEntity::class, parentColumns = ["id"], childColumns = ["portfolioId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("portfolioId")],
)
data class ImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portfolioId: Long,
    val broker: String,
    val fileName: String,
    val importedAt: Long,
    val added: Int,
    val duplicates: Int,
)

@Entity(tableName = "quotes")
data class QuoteEntity(
    /** Instrument key used by the portfolio (ticker or ISIN). */
    @PrimaryKey val key: String,
    val price: String,
    val change: String,
    val changePercent: String,
    val updatedAt: Long,
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val key: String,
    /** Ticker used to query prices (differs from [key] when the key is an ISIN). */
    val ticker: String?,
    val name: String?,
    val sector: String?,
    val country: String?,
    val updatedAt: Long,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    /** "ABOVE" or "BELOW". */
    val condition: String,
    val target: String,
    val active: Boolean = true,
    val triggeredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
