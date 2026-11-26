package com.portafolios.core.importer.brokers

import com.portafolios.core.importer.*
import com.portafolios.core.model.Broker
import com.portafolios.core.model.TxType
import java.math.BigDecimal

/**
 * Quantfury "Informe del historial de trading" / "Trading history report" (PDF).
 * Each row is one action on a position: open, add, reduce or close, either buying or selling.
 * Selling to open or add is a short; buying to reduce or close a short is a cover.
 * The "Valor" column is the invested amount, not quantity × price, so quantity and price are read directly.
 */
class QuantfuryParser : HeaderParser(Broker.QUANTFURY) {
    private var dayFirst: Boolean? = null
    private val action = Regex("""^(COMPRAR|VENDER|BUY|SELL)\s*\((abrir|añadir|reducir|cerrar|open|add|reduce|close)\)$""", RegexOption.IGNORE_CASE)
    private val pair = Regex("""^([A-Z0-9]+)/([A-Z]+)$""")

    override val monthFirst: Boolean? get() = dayFirst?.let { !it }

    override fun matches(cols: Columns) =
        (cols.has("Símbolo") || cols.has("Symbol")) && (cols.has("Acción") || cols.has("Action")) &&
            (cols.has("Cantidad") || cols.has("Quantity")) && (cols.has("Precio") || cols.has("Price"))

    override fun headerScanRows() = 60

    /** Decides dd/MM vs MM/dd from the whole report: any first component above 12 means day first. */
    override fun beforeTable(table: Table, headerIndex: Int) {
        val dates = table.rows.flatten().mapNotNull { Regex("""^(\d{1,2})/(\d{1,2})/\d{4}""").find(it) }
        dayFirst = when {
            dates.any { it.groupValues[1].toInt() > 12 } -> true
            dates.any { it.groupValues[2].toInt() > 12 } -> false
            else -> null
        }
    }

    override fun read(row: Row): RowResult {
        val m = action.find(row.text("Acción", "Action").orEmpty().trim()) ?: return skip() // titles, totals, repeated headers
        val rawSymbol = row.text("Símbolo", "Symbol") ?: return error("Falta el símbolo")
        val dateCols = arrayOf("Fecha/Hora de la posición", "Position date/time", "Date/Time", "Fecha/Hora")
        val date = row.date(*dateCols) ?: return error("Fecha no válida")
        val time = Times.parse(row.text(*dateCols))
        val qty = row.num("Cantidad", "Quantity")?.abs() ?: return error("Falta la cantidad")
        val price = row.num("Precio", "Price")?.abs() ?: return error("Falta el precio")
        val buy = m.groupValues[1].uppercase().let { it == "COMPRAR" || it == "BUY" }
        val opening = Columns.norm(m.groupValues[2]) in setOf("abrir", "anadir", "open", "add")
        val type = when {
            buy && opening -> TxType.BUY
            !buy && !opening -> TxType.SELL
            !buy && opening -> TxType.SHORT
            else -> TxType.COVER
        }
        // Crypto pairs keep the pair (ETH/USD) so prices are looked up as crypto.
        val symbol = pair.find(rawSymbol)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: rawSymbol
        val closes = Columns.norm(m.groupValues[2]) in setOf("cerrar", "close")
        return ok(tradeTx(date, type, symbol, qty, price, BigDecimal.ZERO, "USD").copy(time = time, closesPosition = closes))
    }
}
