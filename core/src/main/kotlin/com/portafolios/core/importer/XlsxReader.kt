package com.portafolios.core.importer

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal .xlsx reader (no external dependency): shared strings, inline strings and numbers.
 * Dates stored as Excel serial numbers are returned as ISO dates when the cell uses a date style.
 */
object XlsxReader {
    private const val MAX_ENTRY_BYTES = 50 * 1024 * 1024

    fun read(bytes: ByteArray): List<Table> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("xl/") && name.endsWith(".xml") || name.endsWith(".rels")) {
                    entries[name] = readLimited(zip)
                }
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val dateStyles = entries["xl/styles.xml"]?.let { parseDateStyles(it) } ?: emptySet()
        val sheetFiles = sheetNames(entries)
        return sheetFiles.mapNotNull { (title, path) ->
            entries[path]?.let { Table(title, parseSheet(it, shared, dateStyles)) }
        }
    }

    private fun readLimited(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= MAX_ENTRY_BYTES) { "Archivo Excel demasiado grande" }
        }
        return out.toByteArray()
    }

    private fun doc(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // Harden against XXE.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun Element.children(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> =
        doc(bytes).documentElement.children("si").map { si -> si.children("t").joinToString("") { it.textContent } }

    private fun parseDateStyles(bytes: ByteArray): Set<Int> {
        val root = doc(bytes).documentElement
        val customDateFormats = root.children("numFmt").filter {
            val code = it.getAttribute("formatCode").lowercase().replace("\"[^\"]*\"".toRegex(), "")
            (code.contains('d') || code.contains('y')) && !code.contains("0.0")
        }.map { it.getAttribute("numFmtId").toInt() }.toSet()
        val builtInDates = (14..22).toSet() + (45..47).toSet()
        val cellXfs = root.children("cellXfs").firstOrNull()?.children("xf") ?: return emptySet()
        return cellXfs.mapIndexedNotNull { idx, xf ->
            val id = xf.getAttribute("numFmtId").toIntOrNull() ?: return@mapIndexedNotNull null
            if (id in builtInDates || id in customDateFormats) idx else null
        }.toSet()
    }

    private fun sheetNames(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val workbook = entries["xl/workbook.xml"] ?: return emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { r ->
            doc(r).documentElement.children("Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
        } ?: emptyMap()
        return doc(workbook).documentElement.children("sheet").mapIndexed { i, s ->
            val target = rels[s.getAttribute("r:id")]?.removePrefix("/xl/")?.removePrefix("/")
            s.getAttribute("name") to ("xl/" + (target ?: "worksheets/sheet${i + 1}.xml"))
        }
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (row in doc(bytes).documentElement.children("row")) {
            val cells = sortedMapOf<Int, String>()
            var nextCol = 0
            for (c in row.children("c")) {
                val col = c.getAttribute("r").takeIf { it.isNotEmpty() }?.let { columnIndex(it) } ?: nextCol
                nextCol = col + 1
                val type = c.getAttribute("t")
                val raw = c.children("v").firstOrNull()?.textContent
                val value = when (type) {
                    "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> c.children("t").joinToString("") { it.textContent }
                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                    else -> {
                        val style = c.getAttribute("s").toIntOrNull()
                        if (raw != null && style != null && style in dateStyles) excelSerialToIso(raw) else raw
                    }
                } ?: ""
                cells[col] = value.trim()
            }
            if (cells.isEmpty()) continue
            val width = cells.lastKey() + 1
            val list = MutableList(width) { "" }
            cells.forEach { (k, v) -> list[k] = v }
            if (list.any { it.isNotEmpty() }) rows.add(list)
        }
        return rows
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    /** Excel serial date (1900 system) to "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss". */
    internal fun excelSerialToIso(raw: String): String {
        val serial = raw.toDoubleOrNull() ?: return raw
        val days = serial.toLong()
        val date = LocalDate.of(1899, 12, 30).plusDays(days)
        val secs = Math.round((serial - days) * 86400).toInt()
        return if (secs == 0) date.toString()
        else "%s %02d:%02d:%02d".format(date, secs / 3600, (secs % 3600) / 60, secs % 60)
    }
}
