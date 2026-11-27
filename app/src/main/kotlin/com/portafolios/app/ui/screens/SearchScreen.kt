package com.portafolios.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portafolios.app.AppContainer
import com.portafolios.app.alerts.AlertChecker
import com.portafolios.app.alerts.PriceAlertWorker
import com.portafolios.app.data.AlertEntity
import com.portafolios.app.data.WatchEntity
import com.portafolios.app.market.MarketRepository
import com.portafolios.app.ui.*
import com.portafolios.core.importer.Numbers
import com.portafolios.core.model.Quote
import com.portafolios.core.risk.AssetClassifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<MarketRepository.SearchResult>>(emptyList())
    val searching = MutableStateFlow(false)
    val watchlist = c.db.watchlist().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val quotes = c.market.quotes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val hasKey get() = c.market.hasApiKey
    private var job: Job? = null

    fun onQuery(q: String) {
        query.value = q
        job?.cancel()
        job = viewModelScope.launch {
            delay(350) // wait until the user stops typing
            searching.value = true
            results.value = runCatching { c.market.search(q) }.getOrDefault(emptyList())
            searching.value = false
        }
    }

    fun refreshWatchlist() = viewModelScope.launch {
        val symbols = c.db.watchlist().observeAll().first().map { it.symbol }
        if (symbols.isNotEmpty()) runCatching { c.market.refresh(symbols) }
    }
}

@Composable
fun SearchScreen(c: AppContainer, onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val vm: SearchViewModel = viewModel { SearchViewModel(c) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val watch by vm.watchlist.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    LaunchedEffect(watch.size) { vm.refreshWatchlist() }

    Scaffold(topBar = { BackTopBar("Buscar activos", onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                OutlinedTextField(
                    query, vm::onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Nombre o símbolo: Apple, NVDA, BTC…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                if (!vm.hasKey) Text(
                    "Sin clave de Finnhub solo se buscan criptomonedas. Añádela en Ajustes para acciones y ETFs.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            if (query.isBlank()) {
                item { Text("Lista de seguimiento", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                if (watch.isEmpty()) item { EmptyState("Busca un activo y pulsa la estrella para seguirlo aquí.") }
                items(watch, key = { it.symbol }) { w -> AssetRow(w.symbol, w.name.orEmpty(), null, quotes[w.symbol]) { onOpen(w.symbol, w.name.orEmpty()) } }
            } else {
                if (!searching && results.isEmpty()) item { EmptyState("Sin resultados.") }
                items(results, key = { it.symbol }) { r -> AssetRow(r.symbol, r.name, r.kind, quotes[r.symbol]) { onOpen(r.symbol, r.name) } }
            }
        }
    }
}

@Composable
private fun AssetRow(symbol: String, name: String, kind: String?, quote: Quote?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(symbol, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(listOfNotNull(name.takeIf { it.isNotBlank() }, kind).joinToString(" · "), maxLines = 1) },
        trailingContent = {
            if (quote != null) Column(horizontalAlignment = Alignment.End) {
                Text(money(quote.price, "USD"))
                Text(percent(quote.changePercent), color = pnlColor(quote.changePercent, MaterialTheme.colorScheme.onSurface), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

class AssetViewModel(private val c: AppContainer, val symbol: String, val name: String) : ViewModel() {
    val quote = c.market.quotes().map { it[symbol] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val profile = c.market.profiles().map { it[symbol] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val alerts = c.db.alerts().observeAll().map { l -> l.filter { it.symbol == symbol } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val watched = c.db.watchlist().observeAll().map { l -> l.any { it.symbol == symbol } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val error = MutableStateFlow<String?>(null)

    fun refresh() = viewModelScope.launch {
        error.value = runCatching { c.market.refresh(listOf(symbol))[symbol] }.getOrElse { it.message }
        AlertChecker.check(c.app, c, c.market.quotes().first().mapValues { it.value.price })
    }

    fun toggleWatch() = viewModelScope.launch {
        if (watched.value) c.db.watchlist().delete(symbol) else c.db.watchlist().upsert(WatchEntity(symbol, name.ifBlank { null }))
    }

    fun addAlert(above: Boolean, price: BigDecimal) = viewModelScope.launch {
        c.db.alerts().insert(AlertEntity(symbol = symbol, condition = if (above) "ABOVE" else "BELOW", target = price.toPlainString()))
    }

    fun deleteAlert(id: Long) = viewModelScope.launch { c.db.alerts().delete(id) }
}

@Composable
fun AssetScreen(c: AppContainer, symbol: String, name: String, onBack: () -> Unit) {
    val vm: AssetViewModel = viewModel(key = "asset-$symbol") { AssetViewModel(c, symbol, name) }
    val quote by vm.quote.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(symbol) {
        while (true) { vm.refresh(); delay(60_000) }
    }

    Scaffold(topBar = {
        BackTopBar(symbol, onBack) {
            IconButton(onClick = { vm.toggleWatch() }) {
                Icon(if (watched) Icons.Filled.Star else Icons.Filled.StarBorder, if (watched) "Quitar de seguimiento" else "Seguir")
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(profile?.name ?: name, style = MaterialTheme.typography.titleMedium)
                    Text(money(quote?.price, "USD"), fontSize = 40.sp, fontWeight = FontWeight.Light)
                    quote?.let { q ->
                        Text("${money(q.change, "USD")} (${percent(q.changePercent)}) hoy", color = pnlColor(q.change, MaterialTheme.colorScheme.onSurfaceVariant))
                        q.timeMillis?.let { t ->
                            val stale = System.currentTimeMillis() - t > 30 * 60_000
                            Text("Precio de las ${priceTime(t)}" + if (stale) " · mercado cerrado" else "", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        listOfNotNull(AssetClassifier.classify(symbol, profile?.sector).label, profile?.sector, profile?.country).distinct().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Alertas de precio", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { adding = true }) { Text("+ Nueva alerta") }
                        }
                        if (alerts.isEmpty()) Text("Sin alertas para $symbol.", style = MaterialTheme.typography.bodySmall)
                        alerts.forEach { a ->
                            val target = BigDecimal(a.target)
                            val dist = quote?.price?.takeIf { it.signum() > 0 }?.let { (target - it).divide(it, 6, RoundingMode.HALF_UP) * BigDecimal(100) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${if (a.condition == "ABOVE") "Si sube a" else "Si baja a"} ${money(target, "USD")}", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (!a.active) "Disparada" else dist?.let { "A ${percent(it)} del precio actual" } ?: "Activa",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = { vm.deleteAlert(a.id) }) { Text("Quitar") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        val price = Numbers.parse(text)
        val current = quote?.price
        val above = if (price != null && current != null) price > current else true
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Alerta para $symbol") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text, { text = it.take(15) }, label = { Text("Precio") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    if (price != null && current != null) {
                        Text("Te avisaremos cuando ${if (above) "suba" else "baje"} a ${money(price, "USD")} (${percent((price - current).divide(current, 6, RoundingMode.HALF_UP) * BigDecimal(100))} desde ahora).")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = price != null && price.signum() > 0, onClick = {
                    vm.addAlert(above, price!!)
                    PriceAlertWorker.schedule(context)
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !AlertChecker.canNotify(context)) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    adding = false
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancelar") } },
        )
    }
}
