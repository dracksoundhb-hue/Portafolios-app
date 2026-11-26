package com.portafolios.core.importer

import com.portafolios.core.model.Broker
import com.portafolios.core.model.Transaction
import com.portafolios.core.model.TxType
import java.math.BigDecimal
import java.time.LocalDate

/** One data row with typed accessors resolved through the header. */
class Row(
    val index: Int,
    private val cols: Columns,
    val values: List<String>,
    private val monthFirst: Boolean?,
    private val decimalComma: Boolean?,
) {
    fun text(vararg aliases: String): String? = values.cell(cols.find(*aliases))
    fun num(vararg aliases: String): BigDecimal? = Numbers.parse(text(*aliases), decimalComma)
    fun date(vararg aliases: String): LocalDate? = Dates.parse(text(*aliases), monthFirst)
    fun col(i: Int?): String? = values.cell(i)
    fun has(vararg aliases: String) = cols.has(*aliases)
    fun columnIndex(vararg aliases: String) = cols.find(*aliases)
}

sealed interface RowResult {
    data class Ok(val tx: Transaction) : RowResult
    data object Skip : RowResult
    data class Error(val message: String) : RowResult
}

/**
 * Base for brokers whose export is a single table with a header row.
 * Subclasses say how to recognize the header and how to read one row.
 */
abstract class HeaderParser(override val broker: Broker) : StatementParser {
    protected open val monthFirst: Boolean? = null
    protected open fun decimalComma(cols: Columns): Boolean? = null
    protected open fun acceptsTable(table: Table): Boolean = true
    /** How many rows from the top to search for the header (PDFs have long preambles). */
    protected open fun headerScanRows(): Int = 15
    protected abstract fun matches(cols: Columns): Boolean
    protected abstract fun read(row: Row): RowResult

    /** Hook for parsers that need context from the rows above the header (account currency...). */
    protected open fun beforeTable(table: Table, headerIndex: Int) {}

    override fun canParse(tables: List<Table>) =
        tables.any { acceptsTable(it) && it.headerRow(headerScanRows(), ::matches) != null }

    override fun parse(tables: List<Table>): ParseResult {
        val txs = mutableListOf<Transaction>()
        val issues = mutableListOf<ParseIssue>()
        var skipped = 0
        for (table in tables.filter(::acceptsTable)) {
            val h = table.headerRow(headerScanRows(), ::matches) ?: continue
            beforeTable(table, h)
            val cols = Columns(table.rows[h])
            val dc = decimalComma(cols)
            for (i in h + 1 until table.rows.size) {
                val values = table.rows[i]
                if (values.all { it.isBlank() }) continue
                val row = Row(i + 1, cols, values, monthFirst, dc)
                when (val r = runCatching { read(row) }.getOrElse { RowResult.Error(it.message ?: "Fila no válida") }) {
                    is RowResult.Ok -> txs.add(r.tx)
                    RowResult.Skip -> skipped++
                    is RowResult.Error -> issues.add(ParseIssue(i + 1, r.message))
                }
            }
        }
        return ParseResult(txs, issues, skipped)
    }

    protected fun ok(tx: Transaction) = RowResult.Ok(tx)
    protected fun skip() = RowResult.Skip
    protected fun error(msg: String) = RowResult.Error(msg)
}

/** Maps broker wording (English and Spanish) to a transaction type. */
object TypeWords {
    fun classify(text: String?): TxType? {
        if (text.isNullOrBlank()) return null
        val t = Columns.norm(text)
        return when {
            t.contains("split") || t.contains("desdoblamiento") -> TxType.SPLIT
            t.contains("withholding") || t.contains("tax") || t.contains("impuesto") || t.contains("retencion") -> TxType.TAX
            t.contains("dividend") || t.contains("divident") || t == "cdiv" || t == "div" -> TxType.DIVIDEND
            t.contains("interest") || t.contains("interes") || t == "int" -> TxType.INTEREST
            t.contains("withdraw") || t.contains("retirada") || t.contains("retiro") || t == "cashout" -> TxType.WITHDRAWAL
            t.contains("deposit") || t.contains("topup") || t.contains("ingreso") || t == "cashin" -> TxType.DEPOSIT
            t.contains("fee") || t.contains("comision") || t.contains("commission") || t.contains("custody") -> TxType.FEE
            t.contains("buy") || t.contains("compra") || t.contains("bought") || t.contains("purchase") || t == "b" -> TxType.BUY
            t.contains("sell") || t.contains("venta") || t.contains("sold") || t.contains("sale") || t == "s" -> TxType.SELL
            else -> null
        }
    }
}

internal fun BigDecimal?.orZero(): BigDecimal = this ?: BigDecimal.ZERO
internal fun BigDecimal.abs0(): BigDecimal = abs()

/** Builds a cash-only transaction (dividend, deposit, fee...). */
internal fun cashTx(
    date: LocalDate, type: TxType, amount: BigDecimal, currency: String,
    symbol: String? = null, ref: String? = null, name: String? = null,
) = Transaction(date = date, type = type, symbol = symbol, amount = amount.abs(), currency = currency.uppercase(), brokerRef = ref, name = name)

/** Builds a trade; amount defaults to quantity * price. */
internal fun tradeTx(
    date: LocalDate, type: TxType, symbol: String?, quantity: BigDecimal, price: BigDecimal,
    fee: BigDecimal, currency: String, amount: BigDecimal? = null, isin: String? = null,
    name: String? = null, ref: String? = null,
) = Transaction(
    date = date, type = type, symbol = symbol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
    quantity = quantity.abs(), price = price.abs(), fee = fee.abs(),
    amount = (amount ?: quantity * price).abs(), currency = currency.uppercase(),
    isin = isin?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }, name = name, brokerRef = ref,
)
