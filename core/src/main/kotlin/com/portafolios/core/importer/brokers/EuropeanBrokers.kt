package com.portafolios.core.importer.brokers

import com.portafolios.core.importer.*
import com.portafolios.core.model.Broker
import com.portafolios.core.model.TxType
import java.math.BigDecimal
import java.math.RoundingMode

/** Trading 212 "History" CSV export. */
class Trading212Parser : HeaderParser(Broker.TRADING_212) {
    override fun matches(cols: Columns) = cols.has("Action") && cols.has("Time") && cols.has("No. of shares")

    override fun read(row: Row): RowResult {
        val action = row.text("Action") ?: return error("Falta la acción")
        val date = row.date("Time") ?: return error("Fecha no válida")
        val ref = row.text("ID")
        val symbol = row.text("Ticker")
        val totalCurrency = row.text("Currency (Total)") ?: row.text("Currency (Result)") ?: "EUR"
        return when (val type = TypeWords.classify(action)) {
            TxType.BUY, TxType.SELL -> {
                val qty = row.num("No. of shares") ?: return error("Falta la cantidad")
                val price = row.num("Price / share") ?: return error("Falta el precio")
                val currency = row.text("Currency (Price / share)") ?: totalCurrency
                val fee = listOf("Transaction fee", "Currency conversion fee", "Stamp duty reserve tax", "Finra fee", "French transaction tax")
                    .mapNotNull { row.num(it) }.fold(BigDecimal.ZERO) { a, b -> a + b.abs() }
                ok(tradeTx(date, type, symbol, qty, price, fee, currency, isin = row.text("ISIN"), name = row.text("Name"), ref = ref))
            }
            TxType.DIVIDEND, TxType.DEPOSIT, TxType.WITHDRAWAL, TxType.INTEREST, TxType.FEE, TxType.TAX -> {
                val amount = row.num("Total") ?: return error("Falta el importe")
                ok(cashTx(date, type, amount, totalCurrency, symbol, ref, row.text("Name")))
            }
            else -> skip()
        }
    }
}

/** Degiro "Transacciones" / "Transactions" CSV export (Spanish, English and Dutch headers). */
class DegiroParser : HeaderParser(Broker.DEGIRO) {
    override fun matches(cols: Columns) = cols.has("ISIN") && (cols.has("Número") || cols.has("Quantity") || cols.has("Aantal")) &&
        (cols.has("Producto") || cols.has("Product"))

    override fun decimalComma(cols: Columns): Boolean? = if (cols.has("Número") || cols.has("Aantal")) true else null

    override fun read(row: Row): RowResult {
        val date = row.date("Fecha", "Date", "Datum") ?: return error("Fecha no válida")
        val qty = row.num("Número", "Quantity", "Aantal") ?: return error("Falta la cantidad")
        if (qty.signum() == 0) return skip()
        val priceIdx = row.columnIndex("Precio", "Price", "Koers")
        val price = row.num("Precio", "Price", "Koers") ?: return error("Falta el precio")
        val currency = row.col(priceIdx?.plus(1))?.takeIf { it.length == 3 } ?: "EUR"
        val fee = row.num("Costes de transacción", "Costes de transacción y/o externos de AutoFX", "Transaction and/or third", "Transaction costs", "Transactiekosten en/of kosten van derden").orZero()
        val type = if (qty.signum() > 0) TxType.BUY else TxType.SELL
        return ok(
            tradeTx(
                date, type, symbol = null, quantity = qty, price = price, fee = fee, currency = currency,
                isin = row.text("ISIN"), name = row.text("Producto", "Product"), ref = row.text("ID Orden", "Order ID", "Order Id"),
            ),
        )
    }
}

/** Revolut trading account statement CSV. */
class RevolutParser : HeaderParser(Broker.REVOLUT) {
    override fun matches(cols: Columns) = cols.has("Ticker") && cols.has("Type") && cols.has("Total Amount") &&
        (cols.has("Price per share") || cols.has("Price per Share"))

    override fun read(row: Row): RowResult {
        val date = row.date("Date") ?: return error("Fecha no válida")
        val typeText = row.text("Type") ?: return error("Falta el tipo")
        val currency = row.text("Currency") ?: "USD"
        val symbol = row.text("Ticker")
        if (Columns.norm(typeText).contains("transfer")) return skip()
        return when (val type = TypeWords.classify(typeText)) {
            TxType.BUY, TxType.SELL -> {
                val qty = row.num("Quantity") ?: return error("Falta la cantidad")
                val price = row.num("Price per share") ?: return error("Falta el precio")
                val total = row.num("Total Amount")
                // Revolut's total includes commission; the difference is the fee.
                val gross = qty.abs() * price.abs()
                val fee = total?.abs()?.let { if (type == TxType.BUY) it - gross else gross - it }
                    ?.takeIf { it.signum() > 0 && it < gross } ?: BigDecimal.ZERO
                ok(tradeTx(date, type, symbol, qty, price, fee, currency))
            }
            TxType.SPLIT -> error("Split de $symbol: revisa la cantidad manualmente (Revolut no indica la proporción)")
            null -> skip()
            else -> {
                val amount = row.num("Total Amount") ?: return error("Falta el importe")
                ok(cashTx(date, type, amount, currency, symbol))
            }
        }
    }
}

/** XTB account history (.xlsx), "Cash operations" sheet. */
class XtbParser : HeaderParser(Broker.XTB) {
    private var accountCurrency = "EUR"
    private val tradeRegex = Regex("""(BUY|SELL)\s+([0-9.]+)(?:/[0-9.]+)?\s*@\s*([0-9.]+)""", RegexOption.IGNORE_CASE)

    override fun matches(cols: Columns) = cols.has("Type") && cols.has("Comment") && cols.has("Symbol") && cols.has("Amount") && cols.has("Time")

    override fun beforeTable(table: Table, headerIndex: Int) {
        accountCurrency = table.rows.take(headerIndex).flatten()
            .firstOrNull { it.matches(Regex("[A-Z]{3}")) && it in KNOWN_CURRENCIES } ?: "EUR"
    }

    override fun read(row: Row): RowResult {
        val typeText = row.text("Type") ?: return skip()
        if (Columns.norm(typeText) == "total") return skip()
        val date = row.date("Time") ?: return error("Fecha no válida")
        val amount = row.num("Amount") ?: return error("Falta el importe")
        val symbol = row.text("Symbol")?.removeSuffix(".US")
        val ref = row.text("ID")
        val norm = Columns.norm(typeText)
        return when {
            norm.contains("purchase") || norm.contains("sale") -> {
                val m = tradeRegex.find(row.text("Comment").orEmpty()) ?: return error("No se reconoce la operación en el comentario")
                val qty = m.groupValues[2].toBigDecimal()
                val price = m.groupValues[3].toBigDecimal()
                val type = if (norm.contains("purchase")) TxType.BUY else TxType.SELL
                ok(tradeTx(date, type, symbol, qty, price, BigDecimal.ZERO, accountCurrency, amount = amount, ref = ref))
            }
            else -> when (val type = TypeWords.classify(typeText)) {
                null, TxType.BUY, TxType.SELL, TxType.SPLIT -> skip()
                else -> ok(cashTx(date, if (type == TxType.DEPOSIT && amount.signum() < 0) TxType.WITHDRAWAL else type, amount, accountCurrency, symbol, ref))
            }
        }
    }
}

/** eToro account statement (.xlsx), "Account Activity" sheet. */
class EtoroParser : HeaderParser(Broker.ETORO) {
    override fun matches(cols: Columns) = cols.has("Type") && cols.has("Details") && cols.has("Amount") && cols.has("Position ID")

    override fun read(row: Row): RowResult {
        val date = row.date("Date") ?: return error("Fecha no válida")
        val typeText = row.text("Type") ?: return error("Falta el tipo")
        val details = row.text("Details").orEmpty()
        val symbol = details.substringBefore('/').trim().takeIf { it.isNotEmpty() }
        val amount = row.num("Amount").orZero()
        val units = row.num("Units", "Units / Contracts")
        val ref = row.text("Position ID")?.let { "$it:${Columns.norm(typeText)}" }
        val norm = Columns.norm(typeText)
        return when {
            norm.contains("openposition") || norm.contains("positionclosed") || norm.contains("closeposition") -> {
                val qty = units ?: return error("Faltan las unidades")
                if (qty.signum() == 0) return skip()
                val type = if (norm.contains("open")) TxType.BUY else TxType.SELL
                val price = amount.abs().divide(qty.abs(), 8, RoundingMode.HALF_UP)
                ok(tradeTx(date, type, symbol, qty, price, BigDecimal.ZERO, "USD", amount = amount, ref = ref))
            }
            norm.contains("overnight") || norm.contains("sdrt") -> ok(cashTx(date, TxType.FEE, amount, "USD", symbol, ref))
            else -> when (val type = TypeWords.classify(typeText)) {
                null, TxType.BUY, TxType.SELL, TxType.SPLIT -> skip()
                else -> ok(cashTx(date, type, amount, "USD", symbol, ref))
            }
        }
    }
}

internal val KNOWN_CURRENCIES = setOf("EUR", "USD", "GBP", "PLN", "CZK", "CHF", "HUF", "RON", "SEK", "NOK", "DKK", "CAD", "AUD", "JPY", "MXN", "BRL", "CLP", "COP", "ARS")
