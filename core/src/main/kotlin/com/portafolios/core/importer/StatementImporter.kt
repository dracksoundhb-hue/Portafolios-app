package com.portafolios.core.importer

import com.portafolios.core.importer.brokers.*
import com.portafolios.core.model.Broker
import com.portafolios.core.model.Transaction

/** A transaction ready to be saved, with its stable fingerprint. */
data class ImportedRow(val transaction: Transaction, val fingerprint: String, val alreadyImported: Boolean)

data class ImportPreview(
    val broker: Broker,
    val rows: List<ImportedRow>,
    val issues: List<ParseIssue>,
    val skipped: Int,
    /** Set when the file looks like another broker's export than the one the user picked. */
    val suggestedBroker: Broker? = null,
    /** Set when nothing could be read; explains why in Spanish for the UI. */
    val error: String? = null,
) {
    val newRows get() = rows.filterNot { it.alreadyImported }
    val duplicateCount get() = rows.count { it.alreadyImported }
}

object StatementImporter {
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024

    private val dedicated: Map<Broker, StatementParser> = listOf(
        InteractiveBrokersParser(), SchwabParser(), RevolutParser(), XtbParser(), RobinhoodParser(),
        Trading212Parser(), DegiroParser(), YahooFinanceParser(), EtoroParser(), EtradeParser(),
        SnowballParser(), WebullParser(), TastytradeParser(), RobinhoodCryptoParser(), QuantfuryParser(),
    ).associateBy { it.broker }

    fun parserFor(broker: Broker): StatementParser = dedicated[broker] ?: GenericParser(broker)

    /**
     * Reads a statement for [broker]. Nothing is saved: the caller shows the preview and stores
     * [ImportPreview.newRows] only after the user confirms. [existingFingerprints] are the fingerprints
     * already stored for the destination portfolio.
     */
    fun preview(file: StatementFile, broker: Broker, existingFingerprints: Set<String>): ImportPreview {
        if (file.bytes.size > MAX_FILE_BYTES) return ImportPreview(broker, emptyList(), emptyList(), 0, error = "El archivo supera 20 MB.")
        if (file.kind == StatementFile.Kind.UNKNOWN) return ImportPreview(broker, emptyList(), emptyList(), 0, error = "Formato de archivo no reconocido.")
        if (file.kind == StatementFile.Kind.PDF && !file.canReadPdf) return ImportPreview(broker, emptyList(), emptyList(), 0, error = "No se pueden leer PDF aquí. Usa el CSV o Excel: ${broker.fileHint}.")
        val tables = try {
            file.tables()
        } catch (e: PdfPasswordException) {
            return ImportPreview(broker, emptyList(), emptyList(), 0, error = "El PDF está protegido con contraseña. Descárgalo sin contraseña o usa el CSV: ${broker.fileHint}.")
        } catch (e: Exception) {
            return ImportPreview(broker, emptyList(), emptyList(), 0, error = "No se pudo leer el archivo: ${e.message}")
        }
        if (file.kind == StatementFile.Kind.PDF) return previewPdf(tables, broker, existingFingerprints)
        val parser = parserFor(broker)
        if (!parser.canParse(tables)) {
            val suggestion = dedicated.values.firstOrNull { it.broker != broker && it.canParse(tables) }?.broker
            val generic = GenericParser(broker)
            if (suggestion == null && generic.canParse(tables)) return build(broker, generic.parse(tables), existingFingerprints, null)
            return ImportPreview(
                broker, emptyList(), emptyList(), 0, suggestedBroker = suggestion,
                error = if (suggestion != null) "Este archivo parece de ${suggestion.displayName}, no de ${broker.displayName}."
                else "No se reconocen las columnas. Para ${broker.displayName} usa: ${broker.fileHint}.",
            )
        }
        return build(broker, parser.parse(tables), existingFingerprints, null)
    }

    /**
     * PDF: first try the broker's table parser (tabular statements keep their columns), then the generic
     * table reader, then the line-by-line reader for confirmations and free-form statements.
     */
    private fun previewPdf(tables: List<Table>, broker: Broker, existing: Set<String>): ImportPreview {
        if (tables.all { it.rows.isEmpty() }) return ImportPreview(
            broker, emptyList(), emptyList(), 0,
            error = "Este PDF no contiene texto (parece una imagen escaneada). Descarga el extracto original del broker o el CSV: ${broker.fileHint}.",
        )
        val candidates = listOf(parserFor(broker), GenericParser(broker)).filter { it.canParse(tables) }
        for (p in candidates) {
            val r = p.parse(tables)
            if (r.transactions.isNotEmpty()) return build(broker, r, existing, null)
        }
        val r = PdfLineParser(broker).parse(tables)
        if (r.transactions.isEmpty()) return ImportPreview(
            broker, emptyList(), r.issues, 0,
            error = "No se encontraron operaciones en este PDF. Si me pasas un ejemplo, añado su formato. Mientras tanto puedes usar: ${broker.fileHint}.",
        )
        return build(broker, r, existing, null)
    }

    private fun build(broker: Broker, result: ParseResult, existing: Set<String>, suggestion: Broker?): ImportPreview {
        val seen = mutableMapOf<String, Int>()
        val rows = result.transactions.map { tx ->
            val base = tx.fingerprint(0)
            val occurrence = seen.getOrDefault(base, 0)
            seen[base] = occurrence + 1
            val fp = if (occurrence == 0) base else tx.fingerprint(occurrence)
            ImportedRow(tx, fp, fp in existing)
        }
        return ImportPreview(broker, rows, result.issues, result.skipped, suggestion)
    }
}
