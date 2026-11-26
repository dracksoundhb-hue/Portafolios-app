package com.portafolios.core.importer

import com.portafolios.core.model.Broker
import com.portafolios.core.model.Transaction

data class ParseIssue(val row: Int?, val message: String)

data class ParseResult(
    val transactions: List<Transaction>,
    val issues: List<ParseIssue> = emptyList(),
    /** Rows that were understood but deliberately skipped (orders not filled, internal transfers...). */
    val skipped: Int = 0,
)

/** Converts the tables of one broker export into normalized transactions. */
interface StatementParser {
    val broker: Broker

    /** True when the tables look like this broker's export (header check). */
    fun canParse(tables: List<Table>): Boolean

    fun parse(tables: List<Table>): ParseResult
}

/** Helper for header-based parsers: locates the header row in a table. */
internal fun Table.headerRow(maxScan: Int = 15, predicate: (Columns) -> Boolean): Int? =
    rows.take(maxScan).indexOfFirst { predicate(Columns(it)) }.takeIf { it >= 0 }
