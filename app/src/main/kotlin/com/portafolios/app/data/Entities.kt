package com.portafolios.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.portafolios.core.model.Transaction
import com.portafolios.core.risk.AssetClass
import com.portafolios.core.risk.PortfolioPlan
import com.portafolios.core.risk.RiskProfile
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
    /** RiskProfile name, or null for portfolios created before risk profiles existed. */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val riskProfile: String? = null,
    /** Target allocation, "EQUITY=48;BONDS=31;...". */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val targets: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val maxPositionPct: Double? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val rebalanceBandPct: Double? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val horizonYears: Int? = null,
) {
    fun plan(): PortfolioPlan? {
        val profile = riskProfile?.let { runCatching { RiskProfile.valueOf(it) }.getOrNull() } ?: return null
        val t = targets?.split(';')?.mapNotNull { part ->
            val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { AssetClass.valueOf(k) to v.toDouble() }.getOrNull()
        }?.toMap() ?: profile.targets.mapValues { it.value.toDouble() }
        return PortfolioPlan(
            profile, t, maxPositionPct ?: profile.maxPositionPct.toDouble(),
            rebalanceBandPct ?: profile.rebalanceBandPct.toDouble(), horizonYears ?: profile.horizonYears,
        )
    }

    fun withPlan(plan: PortfolioPlan) = copy(
        riskProfile = plan.profile.name,
        targets = plan.targets.entries.joinToString(";") { "${it.key.name}=${it.value}" },
        maxPositionPct = plan.maxPositionPct, rebalanceBandPct = plan.rebalanceBandPct, horizonYears = plan.horizonYears,
    )
}

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
    /** "HH:mm:ss" when the statement gives the time. */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val time: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val closesPosition: Boolean = false,
) {
    fun toModel() = Transaction(
        date = LocalDate.parse(date), type = TxType.valueOf(type), symbol = symbol, quantity = BigDecimal(quantity),
        price = BigDecimal(price), fee = BigDecimal(fee), amount = BigDecimal(amount), currency = currency,
        isin = isin, name = name, brokerRef = brokerRef,
        time = time?.let { java.time.LocalTime.parse(it) }, closesPosition = closesPosition,
    )

    companion object {
        fun from(portfolioId: Long, tx: Transaction, fingerprint: String, importId: Long?) = TransactionEntity(
            portfolioId = portfolioId, date = tx.date.toString(), type = tx.type.name, symbol = tx.symbol, isin = tx.isin,
            name = tx.name, quantity = tx.quantity.toPlainString(), price = tx.price.toPlainString(), fee = tx.fee.toPlainString(),
            amount = tx.amount.toPlainString(), currency = tx.currency, brokerRef = tx.brokerRef, fingerprint = fingerprint, importId = importId,
            time = tx.time?.toString(), closesPosition = tx.closesPosition,
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

@Entity(tableName = "watchlist")
data class WatchEntity(
    @PrimaryKey val symbol: String,
    val name: String?,
    val addedAt: Long = System.currentTimeMillis(),
)
