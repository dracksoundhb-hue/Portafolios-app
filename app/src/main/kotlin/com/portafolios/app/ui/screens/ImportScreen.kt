package com.portafolios.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portafolios.app.AppContainer
import com.portafolios.app.data.ImportEntity
import com.portafolios.app.ui.BackTopBar
import com.portafolios.core.importer.ImportPreview
import com.portafolios.core.importer.StatementFile
import com.portafolios.core.importer.StatementImporter
import com.portafolios.core.model.Broker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImportViewModel(private val c: AppContainer, private val portfolioId: Long) : ViewModel() {
    val portfolio = c.portfolios.portfolio(portfolioId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val history: StateFlow<List<ImportEntity>> = c.portfolios.imports(portfolioId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val broker = MutableStateFlow<Broker?>(null)
    val fileName = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<ImportPreview?>(null)
    val busy = MutableStateFlow(false)
    val result = MutableStateFlow<String?>(null)
    private var fileBytes: ByteArray? = null

    /** Instruments the user unticked ("" = movements without a symbol: deposits, fees...). */
    val excluded = MutableStateFlow<Set<String>>(emptySet())
    /** Only import operations on or after this date. */
    val fromDate = MutableStateFlow<java.time.LocalDate?>(null)

    /** The preview after the user's filters; this is what gets saved. */
    val filtered: StateFlow<ImportPreview?> = combine(preview, excluded, fromDate) { p, ex, from ->
        p?.copy(rows = p.rows.filter { r ->
            (r.transaction.instrumentKey ?: "") !in ex && (from == null || !r.transaction.date.isBefore(from))
        })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggle(key: String) { excluded.value = if (key in excluded.value) excluded.value - key else excluded.value + key }
    fun selectOnly(keys: Set<String>) { excluded.value = (preview.value?.rows?.map { it.transaction.instrumentKey ?: "" }?.toSet() ?: emptySet()) - keys }

    fun currentBroker(): Broker = broker.value
        ?: portfolio.value?.broker?.let { runCatching { Broker.valueOf(it) }.getOrNull() } ?: Broker.OTHER

    fun selectBroker(b: Broker) { broker.value = b; reparse() }

    fun loadFile(context: Context, uri: Uri) = viewModelScope.launch {
        busy.value = true
        result.value = null
        try {
            val (name, bytes) = withContext(Dispatchers.IO) { readUri(context, uri) }
            fileName.value = name
            fileBytes = bytes
            excluded.value = emptySet(); fromDate.value = null
            reparseNow()
        } catch (e: Exception) {
            result.value = e.message ?: "No se pudo leer el archivo"
        } finally {
            busy.value = false
        }
    }

    private fun reparse() = viewModelScope.launch { reparseNow() }

    private suspend fun reparseNow() {
        val bytes = fileBytes ?: return
        val name = fileName.value ?: "archivo"
        val existing = c.portfolios.fingerprints(portfolioId)
        val b = currentBroker()
        preview.value = withContext(Dispatchers.Default) { StatementImporter.preview(StatementFile(name, bytes, com.portafolios.app.data.PdfBoxSource), b, existing) }
    }

    fun confirm() = viewModelScope.launch {
        val p = filtered.value ?: return@launch
        busy.value = true
        val added = c.portfolios.saveImport(portfolioId, fileName.value ?: "archivo", p)
        result.value = "Importadas $added operaciones nuevas" + if (p.duplicateCount > 0) " (${p.duplicateCount} ya estaban)" else ""
        preview.value = null; fileBytes = null; fileName.value = null
        busy.value = false
    }

    fun undo(importId: Long) = viewModelScope.launch { c.portfolios.undoImport(importId) }

    private fun readUri(context: Context, uri: Uri): Pair<String, ByteArray> {
        val cr = context.contentResolver
        var name = "archivo"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                name = cur.getString(0) ?: name
                if (!cur.isNull(1)) size = cur.getLong(1)
            }
        }
        if (size > MAX_BYTES) throw IllegalArgumentException("El archivo supera 20 MB")
        val bytes = cr.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IllegalArgumentException("El archivo supera 20 MB")
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("No se pudo abrir el archivo")
        return name to bytes
    }

    companion object { private const val MAX_BYTES = 20 * 1024 * 1024 }
}

@Composable
fun ImportScreen(c: AppContainer, portfolioId: Long, onBack: () -> Unit) {
    val vm: ImportViewModel = viewModel(key = "import-$portfolioId") { ImportViewModel(c, portfolioId) }
    val portfolio by vm.portfolio.collectAsStateWithLifecycle()
    val brokerSel by vm.broker.collectAsStateWithLifecycle()
    val preview by vm.filtered.collectAsStateWithLifecycle()
    val fullPreview by vm.preview.collectAsStateWithLifecycle()
    val excluded by vm.excluded.collectAsStateWithLifecycle()
    val fromDate by vm.fromDate.collectAsStateWithLifecycle()
    val fileName by vm.fileName.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val broker = brokerSel ?: portfolio?.broker?.let { runCatching { Broker.valueOf(it) }.getOrNull() } ?: Broker.OTHER

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadFile(context, uri)
    }

    Scaffold(topBar = { BackTopBar("Importar extracto", onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Portafolio: ${portfolio?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BrokerPicker(broker) { vm.selectBroker(it) }
                Text(broker.fileHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                Button(
                    onClick = { picker.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/pdf", "application/octet-stream")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (fileName == null) "Elegir archivo (PDF, CSV o Excel)" else "Elegir otro archivo") }
                Text(
                    "El archivo se lee solo en este móvil. Nada se guarda hasta que confirmes. Subir el mismo periodo dos veces es seguro: las operaciones repetidas se ignoran.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            result?.let { item { Card { Text(it, Modifier.padding(12.dp)) } } }
            fullPreview?.takeIf { it.error == null && it.rows.isNotEmpty() }?.let { full ->
                item { ImportFilters(full, excluded, fromDate, vm::toggle, vm::selectOnly) { vm.fromDate.value = it } }
            }
            preview?.let { p -> previewItems(p, fileName.orEmpty(), onConfirm = { vm.confirm() }, onUseBroker = { vm.selectBroker(it) }) }
            if (history.isNotEmpty()) {
                item { Text("Importaciones anteriores", style = MaterialTheme.typography.titleMedium) }
                items(history, key = { it.id }) { h -> HistoryRow(h) { vm.undo(h.id) } }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.previewItems(
    p: ImportPreview, fileName: String, onConfirm: () -> Unit, onUseBroker: (Broker) -> Unit,
) {
    if (p.error != null) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.error!!)
                    p.suggestedBroker?.let { s -> TextButton(onClick = { onUseBroker(s) }) { Text("Usar ${s.displayName}") } }
                }
            }
        }
        return
    }
    item {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(fileName, style = MaterialTheme.typography.titleSmall)
                Text("${p.newRows.size} operaciones nuevas · ${p.duplicateCount} ya importadas · ${p.skipped} filas ignoradas")
                if (p.issues.isNotEmpty()) Text("${p.issues.size} filas con problemas (ver abajo)", color = MaterialTheme.colorScheme.error)
                Button(onClick = onConfirm, enabled = p.newRows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Guardar ${p.newRows.size} operaciones")
                }
            }
        }
    }
    items(p.newRows.take(100)) { r ->
        val t = r.transaction
        val detail = if (t.quantity.signum() != 0 && t.price.signum() != 0) "${t.quantity.toPlainString()} × ${t.price.toPlainString()}" else t.amount.toPlainString()
        ListItem(
            headlineContent = { Text("${typeLabel(t.type.name)} ${t.instrumentKey.orEmpty()}") },
            supportingContent = { Text("${t.date} · $detail ${t.currency}") },
        )
    }
    if (p.newRows.size > 100) item { Text("… y ${p.newRows.size - 100} más", style = MaterialTheme.typography.bodySmall) }
    if (p.issues.isNotEmpty()) {
        item { Text("Filas con problemas", style = MaterialTheme.typography.titleSmall) }
        items(p.issues.take(50)) { i -> Text("Fila ${i.row ?: "?"}: ${i.message}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun HistoryRow(h: ImportEntity, onUndo: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val date = remember(h.importedAt) { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-ES")).format(Date(h.importedAt)) }
    ListItem(
        headlineContent = { Text(h.fileName) },
        supportingContent = { Text("$date · ${runCatching { Broker.valueOf(h.broker).displayName }.getOrDefault(h.broker)} · ${h.added} añadidas, ${h.duplicates} repetidas") },
        trailingContent = { TextButton(onClick = { confirm = true }) { Text("Deshacer") } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("¿Deshacer importación?") },
        text = { Text("Se borran las ${h.added} operaciones que añadió este archivo.") },
        confirmButton = { TextButton(onClick = { confirm = false; onUndo() }) { Text("Deshacer") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ImportFilters(
    full: ImportPreview, excluded: Set<String>, fromDate: java.time.LocalDate?,
    onToggle: (String) -> Unit, onOnly: (Set<String>) -> Unit, onFrom: (java.time.LocalDate?) -> Unit,
) {
    val counts = full.rows.groupingBy { it.transaction.instrumentKey ?: "" }.eachCount().toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it })
    val today = java.time.LocalDate.now()
    var dateText by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("¿Qué quieres importar a este portafolio?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOnly(counts.keys) }) { Text("Todos") }
                TextButton(onClick = { onOnly(emptySet()) }) { Text("Ninguno") }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                counts.forEach { (key, n) ->
                    FilterChip(
                        selected = key !in excluded, onClick = { onToggle(key) },
                        label = { Text(if (key.isEmpty()) "Ingresos, comisiones… ($n)" else "$key ($n)") },
                    )
                }
            }
            Text("Desde", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = fromDate == null, onClick = { onFrom(null); dateText = "" }, label = { Text("Todo el historial") })
                FilterChip(selected = fromDate == today, onClick = { onFrom(today) }, label = { Text("Hoy") })
                FilterChip(selected = fromDate == today.minusDays(7), onClick = { onFrom(today.minusDays(7)) }, label = { Text("Últimos 7 días") })
                FilterChip(selected = fromDate == today.minusDays(30), onClick = { onFrom(today.minusDays(30)) }, label = { Text("Últimos 30 días") })
            }
            OutlinedTextField(
                dateText,
                { dateText = it.take(10); com.portafolios.core.importer.Dates.parse(dateText)?.let(onFrom) },
                label = { Text("Otra fecha (dd/mm/aaaa)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            fromDate?.let { Text("Solo operaciones desde el ${it.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
