package com.portafolios.core.importer.brokers

import com.portafolios.core.importer.*
import com.portafolios.core.model.Broker
import com.portafolios.core.model.Transaction
import com.portafolios.core.model.TxType
import java.math.BigDecimal

/** Interactive Brokers Activity Statement CSV: multi-section file ("Trades", "Dividends", ...). */
class InteractiveBrokersParser : StatementParser {
    override val broker = Broker.INTERACTIVE_BROKERS

    override fun canParse(tables: List<Table>) =
        tables.any { t -> t.rows.any { it.getOrNull(1) == "Header" && it.getOrNull(0) in setOf("Trades", "Statement", "Dividends") } }

    override fun parse(tables: List<Table>): ParseResult {
        val txs = mutableListOf<Transaction>()
        val issues = mutableListOf<ParseIssue>()
        var skipped = 0
        for (table in tables) {
            val headers = mutableMapOf<String, Columns>()
            table.rows.forEachIndexed { i, r ->
                val section = r.getOrNull(0) ?: return@forEachIndexed
                when (r.getOrNull(1)) {
                    "Header" -> headers[section] = Columns(r.drop(2))
                    "Data" -> {
                        val cols = headers[section] ?: return@forEachIndexed
                        val row = Row(i + 1, cols, r.drop(2), monthFirst = null, decimalComma = false)
                        when (val res = runCatching { readRow(section, row) }.getOrElse { RowResult.Error(it.message ?: "Fila no válida") }) {
                            is RowResult.Ok -> txs.add(res.tx)
                            RowResult.Skip -> skipped++
                            is RowResult.Error -> issues.add(ParseIssue(i + 1, res.message))
                        }
                    }
                }
            }
        }
        return ParseResult(txs, issues, skipped)
    }

    private fun readRow(section: String, row: Row): RowResult {
        val currency = row.text("Currency") ?: return RowResult.Skip
        if (currency.startsWith("Total")) return RowResult.Skip
        return when (section) {
            "Trades" -> {
                if (row.text("DataDiscriminator")?.equals("Order", true) == false) return RowResult.Skip
                val category = row.text("Asset Category").orEmpty()
                if (!category.startsWith("Stocks") && !category.contains("ETF")) return RowResult.Skip
                val date = row.date("Date/Time") ?: return RowResult.Error("Fecha no válida")
                val qty = row.num("Quantity") ?: return RowResult.Error("Falta la cantidad")
                val price = row.num("T. Price", "TradePrice") ?: return RowResult.Error("Falta el precio")
                val type = if (qty.signum() >= 0) TxType.BUY else TxType.SELL
                RowResult.Ok(tradeTx(date, type, row.text("Symbol"), qty, price, row.num("Comm/Fee", "Comm in USD").orZero(), currency))
            }
            "Dividends", "Withholding Tax" -> {
                val date = row.date("Date") ?: return RowResult.Skip
                val amount = row.num("Amount") ?: return RowResult.Error("Falta el importe")
                val symbol = row.text("Description")?.substringBefore('(')?.trim()
                val type = if (section == "Dividends") TxType.DIVIDEND else TxType.TAX
                RowResult.Ok(cashTx(date, type, amount, currency, symbol, name = row.text("Description")))
            }
            "Deposits & Withdrawals" -> {
                val date = row.date("Settle Date", "Date") ?: return RowResult.Skip
                val amount = row.num("Amount") ?: return RowResult.Error("Falta el importe")
                RowResult.Ok(cashTx(date, if (amount.signum() >= 0) TxType.DEPOSIT else TxType.WITHDRAWAL, amount, currency))
            }
            "Interest" -> {
                val date = row.date("Date") ?: return RowResult.Skip
                val amount = row.num("Amount") ?: return RowResult.Skip
                RowResult.Ok(cashTx(date, if (amount.signum() >= 0) TxType.INTEREST else TxType.FEE, amount, currency))
            }
            "Fees" -> {
                val date = row.date("Date") ?: return RowResult.Skip
                val amount = row.num("Amount") ?: return RowResult.Skip
                RowResult.Ok(cashTx(date, TxType.FEE, amount, currency))
            }
            else -> RowResult.Skip
        }
    }
}

/** Snowball Analytics CSV export/import format. */
class SnowballParser : HeaderParser(Broker.SNOWBALL_ANALYTICS) {
    override fun matches(cols: Columns) = cols.has("Event") && cols.has("Symbol") && cols.has("Price") && cols.has("Quantity")

    override fun read(row: Row): RowResult {
        val date = row.date("Date") ?: return error("Fecha no válida")
        val event = Columns.norm(row.text("Event").orEmpty())
        val currency = row.text("Currency") ?: "USD"
        val symbol = row.text("Symbol")
        val fee = row.num("FeeTax", "Fee").orZero()
        val type = when (event) {
            "buy" -> TxType.BUY
            "sell" -> TxType.SELL
            "dividend" -> TxType.DIVIDEND
            "cashin" -> TxType.DEPOSIT
            "cashout" -> TxType.WITHDRAWAL
            "fee" -> TxType.FEE
            "split" -> TxType.SPLIT
            else -> return skip()
        }
        val qty = row.num("Quantity").orZero()
        val price = row.num("Price").orZero()
        return when (type) {
            TxType.BUY, TxType.SELL -> ok(tradeTx(date, type, symbol, qty, price, fee, currency))
            TxType.SPLIT -> ok(Transaction(date, TxType.SPLIT, symbol?.uppercase(), quantity = qty.abs(), currency = currency))
            // Snowball writes cash amounts in Price (per share for dividends) and Quantity.
            else -> {
                val amount = if (qty.signum() != 0 && type == TxType.DIVIDEND) qty * price else price.takeIf { it.signum() != 0 } ?: qty
                ok(cashTx(date, type, amount, currency, symbol))
            }
        }
    }
}

/** Yahoo Finance portfolio export: one row per purchase lot. */
class YahooFinanceParser : HeaderParser(Broker.YAHOO_FINANCE) {
    override fun matches(cols: Columns) = cols.has("Symbol") && cols.has("Trade Date") && cols.has("Purchase Price") && cols.has("Quantity")

    override fun read(row: Row): RowResult {
        val date = row.date("Trade Date") ?: return skip() // watchlist rows have no trade date
        val qty = row.num("Quantity") ?: return skip()
        val price = row.num("Purchase Price") ?: return skip()
        if (qty.signum() == 0) return skip()
        return ok(tradeTx(date, TxType.BUY, row.text("Symbol"), qty, price, row.num("Commission").orZero(), "USD"))
    }
}

/**
 * Generic importer used for Quantfury, Apex Clearing and "Otro". Recognizes common column names in
 * English and Spanish. Without a type column, the sign of the quantity decides buy or sell.
 */
class GenericParser(broker: Broker) : HeaderParser(broker) {
    private val dateCols = arrayOf("Date", "Fecha", "Trade Date", "Transaction Date", "Activity Date", "Time", "Fecha operación", "Executed", "Date/Time")
    private val symbolCols = arrayOf("Symbol", "Ticker", "Símbolo", "Simbolo", "Instrument", "Asset", "Activo", "Instrumento")
    private val typeCols = arrayOf("Type", "Action", "Side", "Tipo", "Operación", "Operacion", "Transaction Type", "Acción", "Direction")
    private val qtyCols = arrayOf("Quantity", "Qty", "Shares", "Cantidad", "Títulos", "Titulos", "Units", "Acciones", "Amount of shares")
    private val priceCols = arrayOf("Price", "Precio", "Execution Price", "Avg Price", "Price per share", "Precio unitario", "Fill Price")
    private val feeCols = arrayOf("Fee", "Fees", "Commission", "Comisión", "Comision", "Comisiones", "Fees & Comm")
    private val amountCols = arrayOf("Amount", "Total", "Importe", "Net Amount", "Value", "Valor")
    private val currencyCols = arrayOf("Currency", "Divisa", "Moneda", "CCY")

    override fun matches(cols: Columns) =
        cols.find(*dateCols) != null && cols.find(*symbolCols) != null && (cols.find(*qtyCols) != null || cols.find(*amountCols) != null)

    override fun read(row: Row): RowResult {
        val date = row.date(*dateCols) ?: return error("Fecha no válida")
        val symbol = row.text(*symbolCols)
        val qty = row.num(*qtyCols)
        val price = row.num(*priceCols)
        val amount = row.num(*amountCols)
        val currency = row.text(*currencyCols)?.takeIf { it.length == 3 } ?: "USD"
        val type = TypeWords.classify(row.text(*typeCols))
            ?: when {
                qty != null && price != null && qty.signum() > 0 -> TxType.BUY
                qty != null && price != null && qty.signum() < 0 -> TxType.SELL
                else -> return error("No se reconoce el tipo de operación")
            }
        return when (type) {
            TxType.BUY, TxType.SELL -> {
                if (qty == null || qty.signum() == 0) return error("Falta la cantidad")
                val p = price ?: amount?.abs()?.divide(qty.abs(), 8, java.math.RoundingMode.HALF_UP) ?: return error("Falta el precio")
                ok(tradeTx(date, type, symbol, qty, p, row.num(*feeCols).orZero(), currency, amount = amount?.takeIf { it.signum() != 0 }))
            }
            TxType.SPLIT -> error("Split de $symbol: revisa la cantidad manualmente")
            else -> {
                val a = amount ?: return error("Falta el importe")
                ok(cashTx(date, type, a, currency, symbol))
            }
        }
    }
}
