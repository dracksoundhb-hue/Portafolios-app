package com.portafolios.core.importer

import com.portafolios.core.importer.brokers.KNOWN_CURRENCIES
import com.portafolios.core.model.Broker
import com.portafolios.core.model.TxType
import java.math.BigDecimal

/**
 * Fallback for PDF statements without a recognizable table header (trade confirmations, monthly
 * statements). Reads each line on its own: a date, an operation word, a ticker or ISIN, and numbers.
 * For trades it looks for quantity × price = total among the numbers so order ids or balances are not
 * mistaken for prices. Lines it cannot read confidently are reported, never guessed.
 */
class PdfLineParser(override val broker: Broker) : StatementParser {
    private val monthFirst = broker in US_BROKERS
    private val defaultCurrency = if (broker in US_BROKERS || broker == Broker.ETORO) "USD" else "EUR"

    override fun canParse(tables: List<Table>) = true

    override fun parse(tables: List<Table>): ParseResult {
        val txs = mutableListOf<com.portafolios.core.model.Transaction>()
        val issues = mutableListOf<ParseIssue>()
        for (table in tables) table.rows.forEachIndexed { i, cells ->
            val line = cells.joinToString("  ")
            when (val r = readLine(line)) {
                is RowResult.Ok -> txs += r.tx
                is RowResult.Error -> issues += ParseIssue(i + 1, "${r.message}: «${line.take(80)}»")
                RowResult.Skip -> {}
            }
        }
        return ParseResult(txs, issues)
    }

    internal fun readLine(line: String): RowResult {
        val dateMatch = DATE_TOKEN.find(line) ?: return RowResult.Skip
        val date = Dates.parse(dateMatch.value, monthFirst) ?: return RowResult.Skip
        val words = WORD.findAll(line).map { it.value }.toList()
        val type = detectType(words) ?: return RowResult.Skip

        val isin = ISIN.find(line)?.value
        val pair = PAIR.find(line)?.groupValues?.get(1)
        val ticker = pair ?: if (isin != null) null else words.firstOrNull { w ->
            TICKER.matches(w) && w.uppercase() == w && Columns.norm(w) !in STOP_WORDS && w !in KNOWN_CURRENCIES && TypeWords.classify(w) == null && w != isin
        }
        val currency = words.firstOrNull { it in KNOWN_CURRENCIES } ?: defaultCurrency

        // Numbers, without the date, times and the ISIN.
        val cleaned = line.replace(dateMatch.value, " ").replace(TIME, " ").let { if (isin != null) it.replace(isin, " ") else it }
        val numbers = NUMBER.findAll(cleaned).mapNotNull { Numbers.parse(it.value)?.abs() }.filter { it.signum() > 0 }.toList()

        return when (type) {
            TxType.BUY, TxType.SELL -> {
                if (ticker == null && isin == null) return RowResult.Error("Operación sin símbolo reconocible")
                val trade = findTrade(numbers) ?: return RowResult.Error("No se identifican cantidad y precio")
                val (qty, price, total) = trade
                val gross = qty * price
                val fee = total?.let { if (type == TxType.BUY) it - gross else gross - it }
                    ?.takeIf { it.signum() > 0 && it <= gross.multiply(BigDecimal("0.05")) } ?: BigDecimal.ZERO
                RowResult.Ok(tradeTx(date, type, ticker, qty, price, fee, currency, isin = isin))
            }
            TxType.SPLIT -> RowResult.Error("Split: revisa la cantidad manualmente")
            else -> {
                val amount = numbers.lastOrNull() ?: return RowResult.Error("Falta el importe")
                RowResult.Ok(cashTx(date, type, amount, currency, ticker ?: isin))
            }
        }
    }

    private fun detectType(words: List<String>): TxType? {
        // Quantfury and other CFD-style brokers talk about opening/closing long positions.
        val norm = words.map { Columns.norm(it) }
        if ("long" in norm) {
            if (norm.any { it == "open" || it == "opened" || it == "abrir" || it == "apertura" }) return TxType.BUY
            if (norm.any { it == "close" || it == "closed" || it == "cerrar" || it == "cierre" }) return TxType.SELL
        }
        val found = words.filter { it.length > 1 || it == "B" || it == "S" }.mapNotNull { TypeWords.classify(it) }
        return found.firstOrNull { it == TxType.BUY || it == TxType.SELL }
            ?: TxType.TAX.takeIf { it in found }
            ?: found.firstOrNull()
    }

    /** Finds quantity and price such that quantity × price matches another number on the line (±1%). */
    private fun findTrade(nums: List<BigDecimal>): Triple<BigDecimal, BigDecimal, BigDecimal?>? {
        for (i in nums.indices) for (j in nums.indices) {
            if (i == j) continue
            val product = nums[i] * nums[j]
            val total = nums.withIndex().firstOrNull { (k, v) ->
                k != i && k != j && (product - v).abs() <= v.multiply(BigDecimal("0.01")) + BigDecimal("0.02")
            }?.value
            if (total != null) {
                // Quantity is usually written before price; prefer the earlier one as quantity.
                return if (i < j) Triple(nums[i], nums[j], total) else Triple(nums[j], nums[i], total)
            }
        }
        return null
    }

    companion object {
        private val US_BROKERS = setOf(
            Broker.CHARLES_SCHWAB, Broker.ROBINHOOD, Broker.ROBINHOOD_CRYPTO, Broker.ETRADE, Broker.WEBULL,
            Broker.TASTYTRADE, Broker.APEX_CLEARING, Broker.INTERACTIVE_BROKERS, Broker.YAHOO_FINANCE,
        )
        private val DATE_TOKEN = Regex("""\b(\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2} [A-Za-z]{3}\.? \d{4}|[A-Za-z]{3} \d{1,2}, \d{4})\b""")
        /** Crypto or FX pairs such as BTC/USDT or AAPL/USD: the base symbol is the instrument. */
        private val PAIR = Regex("""\b([A-Z][A-Z0-9]{1,9})/(USDT|USDC|USD|EUR|BTC)\b""")
        private val TIME = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
        private val ISIN = Regex("""\b[A-Z]{2}[A-Z0-9]{9}\d\b""")
        private val WORD = Regex("""[\p{L}][\p{L}0-9.\-]*""")
        private val TICKER = Regex("""^[A-Z][A-Z0-9]{0,5}(\.[A-Z]{1,2})?$""")
        private val NUMBER = Regex("""\(?-?[$€£]?\s?\d[\d.,']*\d\)?|\b\d\b""")
        private val STOP_WORDS = setOf(
            "inc", "corp", "ltd", "plc", "etf", "the", "and", "of", "co", "sa", "nv", "ag", "se", "class", "shares", "share",
            "total", "page", "pagina", "fecha", "date", "qty", "price", "precio", "importe", "amount", "id", "ref", "order",
            "market", "limit", "account", "cuenta", "trade", "settlement", "usd", "eur", "acc", "dist", "ucits", "isin",
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
            "ene", "abr", "ago", "dic",
        )
    }
}
