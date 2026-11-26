package com.portafolios.core.importer

/** A sheet of rows read from a CSV file or an Excel worksheet. */
data class Table(val name: String, val rows: List<List<String>>)

/** Raw file chosen by the user. */
class StatementFile(val fileName: String, val bytes: ByteArray) {
    enum class Kind { CSV, XLSX, PDF, UNKNOWN }

    val kind: Kind
        get() = when {
            bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() -> Kind.PDF
            bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> Kind.XLSX
            fileName.lowercase().let { it.endsWith(".csv") || it.endsWith(".txt") || it.endsWith(".tsv") } -> Kind.CSV
            bytes.isNotEmpty() && bytes.take(512).none { it == 0.toByte() } -> Kind.CSV
            else -> Kind.UNKNOWN
        }

    fun tables(): List<Table> = when (kind) {
        Kind.CSV -> listOf(Table(fileName, CsvReader.read(decodeText(bytes))))
        Kind.XLSX -> XlsxReader.read(bytes)
        Kind.PDF, Kind.UNKNOWN -> emptyList()
    }

    companion object {
        fun decodeText(bytes: ByteArray): String {
            // UTF-8 BOM / UTF-16 BOM handling; fall back to Latin-1 when the bytes are not valid UTF-8.
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            val decoder = Charsets.UTF_8.newDecoder()
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                String(bytes, start, bytes.size - start, Charsets.ISO_8859_1)
            }
        }
    }
}
