package com.portafolios.core.importer.brokers

import com.portafolios.core.importer.*
import com.portafolios.core.model.Broker
import com.portafolios.core.model.TxType
import java.math.BigDecimal

/** Charles Schwab transaction history CSV. */
class SchwabParser : HeaderParser(Broker.CHARLES_SCHWAB) {
    override val monthFirst = true
    override fun matches(cols: Columns) = cols.has("Action") && cols.has("Symbol") && cols.has("Fees & Comm") && cols.has("Amount")

    override fun read(row: Row): RowResult {
        val date = row.date("Date") ?: return skip() // footer rows ("Transactions Total")
        val action = row.text("Action") ?: return error("Falta la acción")
        val symbol = row.text("Symbol")
        val amount = row.num("Amount").orZero()
        val norm = Columns.norm(action)
        val type = when {
            norm == "reinvestshares" -> TxType.BUY
            norm.contains("reinvestdividend") -> TxType.DIVIDEND
            norm.contains("journal") || norm.contains("moneylink") || norm.contains("wire") || norm.contains("transfer") ->
                if (amount.signum() >= 0) TxType.DEPOSIT else TxType.WITHDRAWAL
            else -> TypeWords.classify(action)
        } ?: return skip()
        return when (type) {
            TxType.BUY, TxType.SELL -> {
                val qty = row.num("Quantity") ?: return error("Falta la cantidad")
                val price = row.num("Price") ?: return error("Falta el precio")
                ok(tradeTx(date, type, symbol, qty, price, row.num("Fees & Comm").orZero(), "USD", amount = amount.takeIf { it.signum() != 0 }))
            }
            TxType.SPLIT -> error("Split de $symbol: revisa la cantidad manualmente")
            else -> if (amount.signum() == 0) skip() else ok(cashTx(date, type, amount, "USD", symbol))
        }
    }
}

/** Robinhood account activity CSV (also used for Robinhood Crypto). */
open class RobinhoodParser(broker: Broker = Broker.ROBINHOOD) : HeaderParser(broker) {
    override val monthFirst = true
    override fun matches(cols: Columns) = cols.has("Activity Date") && cols.has("Trans Code") && cols.has("Instrument")

    override fun read(row: Row): RowResult {
        val date = row.date("Activity Date") ?: return skip()
        val code = row.text("Trans Code")?.uppercase() ?: return skip()
        val symbol = row.text("Instrument")
        val amount = row.num("Amount").orZero()
        val type = when (code) {
            "BUY", "BTO", "BCXL" -> TxType.BUY
            "SELL", "STC", "SCXL" -> TxType.SELL
            "CDIV", "MDIV", "QDIV" -> TxType.DIVIDEND
            "ACH", "RTP", "DCF", "XENT" -> if (amount.signum() >= 0) TxType.DEPOSIT else TxType.WITHDRAWAL
            "INT", "SLIP" -> TxType.INTEREST
            "DTAX", "NRA", "FTAX" -> TxType.TAX
            "GOLD", "DFEE", "AFEE", "SFEE" -> TxType.FEE
            "SPL", "SPR" -> return error("Split de $symbol: revisa la cantidad manualmente")
            else -> return skip()
        }
        return when (type) {
            TxType.BUY, TxType.SELL -> {
                val qty = row.num("Quantity") ?: return error("Falta la cantidad")
                val price = row.num("Price") ?: return error("Falta el precio")
                ok(tradeTx(date, type, symbol, qty, price, BigDecimal.ZERO, "USD", amount = amount.takeIf { it.signum() != 0 }))
            }
            else -> if (amount.signum() == 0) skip() else ok(cashTx(date, type, amount, "USD", symbol))
        }
    }
}

class RobinhoodCryptoParser : RobinhoodParser(Broker.ROBINHOOD_CRYPTO)

/** E*Trade transaction history CSV. */
class EtradeParser : HeaderParser(Broker.ETRADE) {
    override val monthFirst = true
    override fun matches(cols: Columns) = cols.has("TransactionDate") && cols.has("TransactionType") && cols.has("Symbol")

    override fun read(row: Row): RowResult {
        val date = row.date("TransactionDate") ?: return skip()
        val typeText = row.text("TransactionType") ?: return skip()
        val symbol = row.text("Symbol")
        val amount = row.num("Amount").orZero()
        val type = when (Columns.norm(typeText)) {
            "bought" -> TxType.BUY
            "sold" -> TxType.SELL
            "transfer", "onlinetransfer", "ach" -> if (amount.signum() >= 0) TxType.DEPOSIT else TxType.WITHDRAWAL
            else -> TypeWords.classify(typeText)
        } ?: return skip()
        return when (type) {
            TxType.BUY, TxType.SELL -> {
                val qty = row.num("Quantity") ?: return error("Falta la cantidad")
                val price = row.num("Price") ?: return error("Falta el precio")
                ok(tradeTx(date, type, symbol, qty, price, row.num("Commission").orZero(), "USD", amount = amount.takeIf { it.signum() != 0 }))
            }
            TxType.SPLIT -> error("Split de $symbol: revisa la cantidad manualmente")
            else -> if (amount.signum() == 0) skip() else ok(cashTx(date, type, amount, "USD", symbol))
        }
    }
}

/** Webull order history CSV. Only filled orders are imported. */
class WebullParser : HeaderParser(Broker.WEBULL) {
    override val monthFirst = true
    override fun matches(cols: Columns) = cols.has("Side") && cols.has("Status") && cols.has("Filled") && cols.has("Symbol")

    override fun read(row: Row): RowResult {
        if (!row.text("Status").equals("Filled", ignoreCase = true)) return skip()
        val date = row.date("Filled Time", "Placed Time") ?: return error("Fecha no válida")
        val type = TypeWords.classify(row.text("Side")) ?: return error("Lado no reconocido")
        if (type != TxType.BUY && type != TxType.SELL) return skip()
        val qty = row.num("Filled") ?: return error("Falta la cantidad")
        val price = row.num("Avg Price", "Price") ?: return error("Falta el precio")
        return ok(tradeTx(date, type, row.text("Symbol"), qty, price, BigDecimal.ZERO, "USD"))
    }
}

/** tastytrade transaction history CSV. Stocks, ETFs and crypto only; options are skipped. */
class TastytradeParser : HeaderParser(Broker.TASTYTRADE) {
    override fun matches(cols: Columns) = cols.has("Instrument Type") && cols.has("Action") && cols.has("Value") && cols.has("Sub Type")

    override fun read(row: Row): RowResult {
        val date = row.date("Date") ?: return error("Fecha no válida")
        val kind = Columns.norm(row.text("Type").orEmpty())
        val currency = row.text("Currency") ?: "USD"
        val value = row.num("Value").orZero()
        return when (kind) {
            "trade", "receivedeliver" -> {
                val instrument = Columns.norm(row.text("Instrument Type").orEmpty())
                if (instrument != "equity" && instrument != "cryptocurrency") return skip()
                val action = row.text("Action").orEmpty().uppercase()
                val type = when {
                    action.startsWith("BUY") -> TxType.BUY
                    action.startsWith("SELL") -> TxType.SELL
                    else -> return skip()
                }
                val qty = row.num("Quantity") ?: return error("Falta la cantidad")
                val price = row.num("Average Price") ?: return error("Falta el precio")
                val fee = row.num("Commissions").orZero().abs() + row.num("Fees").orZero().abs()
                ok(tradeTx(date, type, row.text("Symbol", "Underlying Symbol"), qty, price, fee, currency, amount = value.takeIf { it.signum() != 0 }))
            }
            "moneymovement" -> {
                val sub = row.text("Sub Type")
                val type = when (Columns.norm(sub.orEmpty())) {
                    "deposit" -> TxType.DEPOSIT
                    "withdrawal" -> TxType.WITHDRAWAL
                    "balanceadjustment" -> if (value.signum() >= 0) TxType.DEPOSIT else TxType.WITHDRAWAL
                    else -> TypeWords.classify(sub)
                } ?: return skip()
                if (value.signum() == 0) skip() else ok(cashTx(date, type, value, currency, row.text("Symbol")))
            }
            else -> skip()
        }
    }
}
