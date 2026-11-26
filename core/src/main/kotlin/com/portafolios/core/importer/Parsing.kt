package com.portafolios.core.importer

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object Numbers {
    /**
     * Parses amounts as brokers write them: "$1,234.56", "(1,500.00)", "1.234,56", "USD 150.00",
     * "-12.5 €". Returns null for empty or non-numeric cells.
     * [decimalComma] forces the decimal separator when the format is known; otherwise it is guessed.
     */
    fun parse(text: String?, decimalComma: Boolean? = null): BigDecimal? {
        if (text == null) return null
        var s = text.trim()
        if (s.isEmpty() || s == "-" || s == "--" || s.equals("n/a", true)) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        s = s.replace(" ", "").replace(" ", "").replace("'", "")
        s = s.filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' || it == 'E' || it == 'e' }
            .let { cleaned -> if (cleaned.any { it.isDigit() }) cleaned else return null }
        // Remove stray letters 'E'/'e' that are not exponent markers (e.g. "EUR" already removed by filter → "E").
        if (!Regex("^[+-]?[0-9.,]+([eE][+-]?[0-9]+)?$").matches(s)) {
            s = s.replace("E", "").replace("e", "")
        }
        if (s.endsWith("-")) { negative = !negative; s = s.dropLast(1) }
        if (s.startsWith("-")) { negative = !negative; s = s.drop(1) }
        if (s.startsWith("+")) s = s.drop(1)
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val useComma = when {
            decimalComma != null -> decimalComma
            lastDot >= 0 && lastComma >= 0 -> lastComma > lastDot
            lastComma >= 0 -> {
                // "1,234" is ambiguous: treat as thousands only when grouped in threes and there is no other hint.
                val groups = s.split(',')
                !(groups.size >= 2 && groups.drop(1).all { it.length == 3 })
            }
            else -> false
        }
        s = if (useComma) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        val value = s.toBigDecimalOrNull() ?: return null
        return if (negative) value.negate() else value
    }
}

object Dates {
    private fun f(p: String) = DateTimeFormatter.ofPattern(p, java.util.Locale.ENGLISH)
    private val iso = listOf("yyyy-M-d", "yyyy/M/d", "yyyyMMdd").map(::f)
    private val dayFirst = listOf("d-M-yyyy", "d/M/yyyy", "d.M.yyyy", "d/M/yy", "d-M-yy").map(::f)
    private val monthFirstPatterns = listOf("M/d/yyyy", "M-d-yyyy", "M/d/yy").map(::f)
    private val named = listOf("d MMM yyyy", "MMM d, yyyy", "d-MMM-yyyy").map(::f)

    /**
     * Parses the date part of a date or date-time cell. [monthFirst] resolves "01/02/2024":
     * true for US brokers (MM/dd), false or null for European ones (dd/MM).
     */
    fun parse(text: String?, monthFirst: Boolean? = null): LocalDate? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        runCatching { return OffsetDateTime.parse(t).toLocalDate() }
        runCatching { return ZonedDateTime.parse(t).toLocalDate() }
        runCatching { return LocalDateTime.parse(t.replace(' ', 'T').substringBefore('.')).toLocalDate() }
        // Keep only the date token: "2024-01-15 10:30:00", "2024-01-15, 10:30:00", "01/15/2024 as of 01/14/2024".
        val token = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{8}|\d{1,2}[ -][A-Za-z]{3}[ -]\d{4}|[A-Za-z]{3} \d{1,2}, \d{4}""")
            .find(t)?.value ?: t
        val ordered = iso + (if (monthFirst == true) monthFirstPatterns + dayFirst else dayFirst + monthFirstPatterns) + named
        for (fmt in ordered) {
            try {
                val d = LocalDate.parse(token, fmt)
                if (d.year in 1950..2100) return d
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }
}

/** Finds columns by name, ignoring case, accents and punctuation, with several aliases per field. */
class Columns(header: List<String>) {
    private val index: Map<String, Int> = header.mapIndexed { i, h -> norm(h) to i }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.first() }

    fun find(vararg aliases: String): Int? = aliases.firstNotNullOfOrNull { index[norm(it)] }

    fun has(vararg aliases: String): Boolean = find(*aliases) != null

    companion object {
        fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().filter { it.isLetterOrDigit() }
    }
}

fun List<String>.cell(i: Int?): String? = i?.let { getOrNull(it) }?.takeIf { it.isNotBlank() }
