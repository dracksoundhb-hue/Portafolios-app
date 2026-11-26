package com.portafolios.core.importer

/** RFC 4180 CSV reader with delimiter detection (comma, semicolon, tab). */
object CsvReader {
    fun read(text: String, delimiter: Char? = null): List<List<String>> {
        val sep = delimiter ?: detectDelimiter(text)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                sep -> { row.add(field.toString().trim()); field.clear() }
                '\r' -> {}
                '\n' -> {
                    row.add(field.toString().trim()); field.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(5).toList()
        return listOf(',', ';', '\t').maxByOrNull { d -> sample.sumOf { line -> countOutsideQuotes(line, d) } } ?: ','
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var inQ = false
        var n = 0
        for (c in line) {
            if (c == '"') inQ = !inQ else if (c == d && !inQ) n++
        }
        return n
    }
}
