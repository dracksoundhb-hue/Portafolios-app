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
        SnowballParser(), WebullParser(), TastytradeParser(), RobinhoodCryptoParser(),
    ).associateBy { it.broker }

    fun parserFor(broker: Broker): StatementParser = dedicated[broker] ?: GenericParser(broker)

    /**
     * Reads a statement for [broker]. Nothing is saved: the caller shows the preview and stores
     * [ImportPreview.newRows] only after the user confirms. [existingFingerprints] are the fingerprints
     * already stored for the destination portfolio.
     */
    fun preview(file: StatementFile, broker: Broker, existingFingerprints: Set<String>): ImportPreview {
        if (file.bytes.size > MAX_FILE_BYTES) return ImportPreview(broker, emptyList(), emptyList(), 0, error = "El archivo supera 20 MB.")
        when (file.kind) {
            StatementFile.Kind.PDF -> return ImportPreview(
                broker, emptyList(), emptyList(), 0,
                error = "Los PDF todavía no están soportados. Descarga el historial en CSV o Excel: ${broker.fileHint}.",
            )
            StatementFile.Kind.UNKNOWN -> return ImportPreview(broker, emptyList(), emptyList(), 0, error = "Formato de archivo no reconocido.")
            else -> {}
        }
        val tables = runCatching { file.tables() }.getOrElse {
            return ImportPreview(broker, emptyList(), emptyList(), 0, error = "No se pudo leer el archivo: ${it.message}")
        }
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
