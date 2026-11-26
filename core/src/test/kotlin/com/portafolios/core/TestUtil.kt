package com.portafolios.core

import com.portafolios.core.importer.ImportPreview
import com.portafolios.core.importer.StatementFile
import com.portafolios.core.importer.StatementImporter
import com.portafolios.core.model.Broker
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun csv(name: String, text: String) = StatementFile(name, text.trimIndent().toByteArray())

fun preview(file: StatementFile, broker: Broker, existing: Set<String> = emptySet()): ImportPreview =
    StatementImporter.preview(file, broker, existing)

/** Builds a tiny .xlsx with one sheet of inline strings / numbers (cells starting with '#' are numbers). */
fun xlsx(sheetName: String, rows: List<List<String>>): StatementFile {
    fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }
    val sheet = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, v ->
                val ref = col(c) + (r + 1)
                if (v.startsWith("#")) append("<c r=\"$ref\"><v>${v.drop(1)}</v></c>")
                else append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${v.replace("&", "&amp;").replace("<", "&lt;")}</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
        put("[Content_Types].xml", "<Types/>")
        put("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Info" sheetId="1" r:id="rId1"/><sheet name="$sheetName" sheetId="2" r:id="rId2"/></sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>""")
        put("xl/worksheets/sheet1.xml", """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Resumen</t></is></c></row></sheetData></worksheet>""")
        put("xl/worksheets/sheet2.xml", sheet)
    }
    return StatementFile("statement.xlsx", out.toByteArray())
}
