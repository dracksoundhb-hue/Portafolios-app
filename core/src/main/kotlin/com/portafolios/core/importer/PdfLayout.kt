package com.portafolios.core.importer

/** One character (or text chunk) of a PDF page with its position, in PDF points. */
data class Glyph(val page: Int, val x: Float, val y: Float, val width: Float, val height: Float, val text: String)

/** Extracts positioned text from PDF bytes. Implemented on Android with PdfBox-Android. */
fun interface PdfTextSource {
    fun glyphs(bytes: ByteArray): List<Glyph>
}

class PdfPasswordException : Exception("El PDF está protegido con contraseña")

/**
 * Rebuilds table rows from positioned text: glyphs on the same baseline form a line, and a wide
 * horizontal gap starts a new cell. This keeps the column structure that plain text extraction loses,
 * so the same header-based parsers used for CSV can read tabular PDF statements.
 */
object PdfLayout {
    fun rows(glyphs: List<Glyph>): List<List<String>> {
        val visible = glyphs.filter { it.text.isNotBlank() }
        if (visible.isEmpty()) return emptyList()
        val rows = mutableListOf<List<String>>()
        for ((_, pageGlyphs) in visible.groupBy { it.page }.toSortedMap()) {
            val medianHeight = pageGlyphs.map { it.height }.sorted()[pageGlyphs.size / 2].coerceAtLeast(4f)
            val lines = mutableListOf<MutableList<Glyph>>()
            for (g in pageGlyphs.sortedBy { it.y }) {
                val line = lines.lastOrNull()
                if (line != null && kotlin.math.abs(line.first().y - g.y) <= medianHeight * 0.45f) line.add(g) else lines.add(mutableListOf(g))
            }
            for (line in lines) {
                val sorted = line.sortedBy { it.x }
                val medianWidth = sorted.map { it.width / it.text.length.coerceAtLeast(1) }.sorted()[sorted.size / 2].coerceAtLeast(1f)
                val cells = mutableListOf<String>()
                val cell = StringBuilder()
                var lastEnd = Float.NaN
                for (g in sorted) {
                    if (!lastEnd.isNaN()) {
                        val gap = g.x - lastEnd
                        when {
                            gap > medianWidth * 1.8f -> { cells.add(cell.toString().trim()); cell.clear() }
                            gap > medianWidth * 0.2f -> cell.append(' ')
                        }
                    }
                    cell.append(g.text)
                    lastEnd = maxOf(if (lastEnd.isNaN()) 0f else lastEnd, g.x + g.width)
                }
                cells.add(cell.toString().trim())
                if (cells.any { it.isNotEmpty() }) rows.add(cells)
            }
        }
        return rows
    }
}
