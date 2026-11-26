package com.portafolios.core

import com.portafolios.core.importer.CsvReader
import com.portafolios.core.importer.Dates
import com.portafolios.core.importer.Numbers
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParsingTest {
    private fun n(s: String, dc: Boolean? = null) = Numbers.parse(s, dc)

    @Test fun numbers() {
        assertEquals(BigDecimal("1234.56"), n("$1,234.56"))
        assertEquals(BigDecimal("-1500.00"), n("($1,500.00)"))
        assertEquals(BigDecimal("1234.56"), n("1.234,56"))
        assertEquals(BigDecimal("150.25"), n("USD 150.25"))
        assertEquals(BigDecimal("12.34"), n("12,34"))
        assertEquals(BigDecimal("-12.5"), n("-12.5 €"))
        assertEquals(BigDecimal("1234"), n("1,234"))
        assertEquals(BigDecimal("1.234"), n("1,234", dc = true))
        assertEquals(BigDecimal("0.00012"), n("1.2E-4"))
        assertNull(n("")); assertNull(n("--")); assertNull(n("abc"))
    }

    @Test fun dates() {
        val d = LocalDate.of(2024, 1, 15)
        assertEquals(d, Dates.parse("2024-01-15 14:30:00"))
        assertEquals(d, Dates.parse("2024-01-15T14:30:00.123Z"))
        assertEquals(d, Dates.parse("2024-01-15, 10:30:00"))
        assertEquals(d, Dates.parse("15-01-2024"))
        assertEquals(d, Dates.parse("15/01/2024 10:00:00"))
        assertEquals(d, Dates.parse("01/15/2024 as of 01/14/2024", monthFirst = true))
        assertEquals(LocalDate.of(2024, 2, 1), Dates.parse("02/01/2024", monthFirst = true))
        assertEquals(LocalDate.of(2024, 1, 2), Dates.parse("02/01/2024"))
        assertEquals(d, Dates.parse("20240115"))
        assertEquals(d, Dates.parse("1/15/24", monthFirst = true))
    }

    @Test fun csvQuotesAndSemicolons() {
        val rows = CsvReader.read("a;b;c\n\"x;1\";\"he said \"\"hi\"\"\";3\n")
        assertEquals(listOf("x;1", "he said \"hi\"", "3"), rows[1])
    }
}
