package com.portafolios.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portafolios.app.AppContainer
import com.portafolios.app.data.PortfolioEntity
import com.portafolios.app.data.TransactionEntity
import com.portafolios.app.ui.*
import com.portafolios.core.analysis.Insight
import com.portafolios.core.analysis.PortfolioAnalyzer
import com.portafolios.core.analysis.Slice
import com.portafolios.core.calc.Holding
import com.portafolios.core.calc.PortfolioCalculator
import com.portafolios.core.calc.PortfolioSummary
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DetailState(
    val portfolio: PortfolioEntity,
    val summary: PortfolioSummary,
    val transactions: List<TransactionEntity>,
    val sectors: List<Slice>,
    val countries: List<Slice>,
    val insights: List<Insight>,
    val plan: com.portafolios.core.risk.PortfolioPlan?,
    val classes: Map<com.portafolios.core.risk.AssetClass, Double>,
)

class PortfolioDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val state: StateFlow<DetailState?> = combine(
        c.portfolios.portfolio(id), c.portfolios.transactions(id), c.market.quotes(), c.market.profiles(),
    ) { p, txs, quotes, profiles ->
        if (p == null) return@combine null
        val summary = PortfolioCalculator.summary(txs.map { it.toModel() }, quotes)
        DetailState(
            p, summary, txs,
            PortfolioAnalyzer.allocation(summary, profiles) { it?.sector },
            PortfolioAnalyzer.allocation(summary, profiles) { it?.country },
            PortfolioAnalyzer.insights(summary, profiles, p.plan()),
            p.plan(),
            PortfolioAnalyzer.classAllocation(summary, profiles),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val refreshing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    private var lastSilentMessage: String? = null

    /** [silent]: automatic refresh, only speaks up on errors. */
    fun refreshPrices(silent: Boolean = false) {
        val keys = state.value?.summary?.holdings?.map { it.position.symbol } ?: return
        if (refreshing.value || keys.isEmpty()) return
        viewModelScope.launch {
            refreshing.value = true
            val msg = try {
                val failed = c.market.refresh(keys)
                // While the app is open, alerts fire right after each refresh instead of waiting for the background check.
                val prices = c.market.quotes().first().mapValues { it.value.price }
                com.portafolios.app.alerts.AlertChecker.check(c.app, c, prices)
                if (failed.isEmpty()) (if (silent) null else "Precios actualizados") else "Sin precio: ${failed.keys.joinToString()}"
            } catch (e: Exception) {
                e.message ?: "No se pudieron actualizar los precios"
            }
            // Automatic refreshes repeat every minute: do not repeat the same warning each time.
            if (msg != null && !(silent && msg == lastSilentMessage)) message.value = msg
            if (silent) lastSilentMessage = msg
            refreshing.value = false
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.portfolios.deletePortfolio(id); onDone() }
    fun deleteTransaction(txId: Long) = viewModelScope.launch { c.portfolios.deleteTransaction(txId) }
    fun rename(name: String) = viewModelScope.launch { state.value?.portfolio?.let { c.portfolios.updatePortfolio(it.copy(name = name.trim())) } }
}

@Composable
fun PortfolioDetailScreen(c: AppContainer, id: Long, onBack: () -> Unit, onImport: () -> Unit, onAlerts: () -> Unit, onPlan: () -> Unit, onPosition: (String) -> Unit) {
    val vm: PortfolioDetailViewModel = viewModel(key = "detail-$id") { PortfolioDetailViewModel(c, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    // Refresh when the portfolio opens and every minute while it stays on screen.
    val hasState = state != null
    LaunchedEffect(hasState) {
        if (!hasState) return@LaunchedEffect
        while (true) {
            vm.refreshPrices(silent = true)
            kotlinx.coroutines.delay(60_000)
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(state?.portfolio?.name ?: "", onBack) {
                IconButton(onClick = { vm.refreshPrices() }, enabled = !refreshing) { Icon(Icons.Filled.Refresh, "Actualizar precios") }
                IconButton(onClick = onImport) { Icon(Icons.Filled.UploadFile, "Importar extracto") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Perfil de riesgo") }, onClick = { menu = false; onPlan() })
                        DropdownMenuItem(text = { Text("Alertas de precio") }, onClick = { menu = false; onAlerts() })
                        DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Eliminar portafolio") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold
        Column(Modifier.padding(padding)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SummaryHeader(s.summary, s.portfolio.baseCurrency, s.transactions.any { it.type == "DEPOSIT" || it.type == "WITHDRAWAL" })
            TabRow(selectedTabIndex = tab) {
                listOf("Posiciones", "Análisis", "Operaciones").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> HoldingsTab(s.summary, onPosition)
                1 -> AnalysisTab(s)
                else -> TransactionsTab(s.transactions) { vm.deleteTransaction(it) }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("¿Eliminar portafolio?") },
        text = { Text("Se borran el portafolio y todas sus operaciones de este móvil. No se puede deshacer.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
    )
    if (renaming) {
        var name by remember { mutableStateOf(state?.portfolio?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renombrar") },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SummaryHeader(s: PortfolioSummary, base: String, cashKnown: Boolean) {
    val cur = s.holdings.firstOrNull()?.position?.currency ?: base
    val neutral = MaterialTheme.colorScheme.onSurface
    val cash = s.cash.entries.filter { it.value.signum() != 0 }.joinToString(" · ") { money(it.value, it.key) }.ifEmpty { "—" }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(money(s.marketValue, cur), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("G/P no realizada", "${money(s.unrealizedPnl, cur)} (${percent(s.unrealizedPnlPercent)})", Modifier.weight(1.4f), pnlColor(s.unrealizedPnl, neutral))
            StatChip("Hoy", money(s.dayChange, cur), Modifier.weight(1f), pnlColor(s.dayChange, neutral))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Invertido", money(s.costBasis, cur), Modifier.weight(1f))
            StatChip("G/P realizada", money(s.realizedPnl, cur), Modifier.weight(1f), pnlColor(s.realizedPnl, neutral))
            StatChip("Dividendos", money(s.dividends, cur), Modifier.weight(1f))
        }
        s.holdings.mapNotNull { it.quote?.timeMillis }.maxOrNull()?.let { t ->
            Text("Último precio: ${priceTime(t)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Some brokers (Quantfury) do not report deposits, so the cash balance cannot be known.
        if (cashKnown) Text("Efectivo: $cash", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HoldingsTab(s: PortfolioSummary, onOpen: (String) -> Unit) {
    if (s.holdings.isEmpty()) { EmptyState("Sin posiciones abiertas. Importa un extracto con el botón de subir archivo."); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.holdings, key = { it.position.symbol }) { h -> HoldingRow(h, s.weights[h.position.symbol]) { onOpen(h.position.symbol) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingRow(h: Holding, weight: java.math.BigDecimal?, onClick: () -> Unit) {
    val p = h.position
    val neutral = MaterialTheme.colorScheme.onSurface
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (p.isShort) "${p.symbol} · corto" else p.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                p.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("${number(p.quantity, 4)} × ${money(p.averageCost, p.currency)} · peso ${percent(weight, signed = false)}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(h.quote?.price, p.currency), style = MaterialTheme.typography.titleSmall)
                Text(percent(h.quote?.changePercent), style = MaterialTheme.typography.bodySmall, color = pnlColor(h.quote?.changePercent, neutral))
                Text(money(h.unrealizedPnl, p.currency), style = MaterialTheme.typography.bodySmall, color = pnlColor(h.unrealizedPnl, neutral))
            }
        }
    }
}

@Composable
private fun AnalysisTab(s: DetailState) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.plan?.let { plan -> item { PlanVsActual(plan, s.classes) } }
        item { Text("Qué revisar", style = MaterialTheme.typography.titleMedium) }
        if (s.insights.isEmpty()) item { Text("Nada destacable. Actualiza precios para un análisis completo.", style = MaterialTheme.typography.bodySmall) }
        items(s.insights) { i ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(i.title, style = MaterialTheme.typography.titleSmall)
                    Text(i.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { DonutChart("Diversificación por sector", s.sectors, Modifier.fillMaxWidth()) }
        item { DonutChart("Distribución por país", s.countries, Modifier.fillMaxWidth()) }
        item {
            Text(
                "Análisis automático con reglas simples. No es asesoramiento financiero.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransactionsTab(txs: List<TransactionEntity>, onDelete: (Long) -> Unit) {
    if (txs.isEmpty()) { EmptyState("Sin operaciones."); return }
    var pending by remember { mutableStateOf<TransactionEntity?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(txs, key = { it.id }) { t ->
            ListItem(
                headlineContent = { Text("${typeLabel(t.type)} ${t.symbol ?: t.isin ?: ""}") },
                supportingContent = {
                    val detail = if (t.type in setOf("BUY", "SELL", "SHORT", "COVER")) "${t.quantity} × ${t.price} ${t.currency}" else "${t.amount} ${t.currency}"
                    Text("${t.date} · $detail")
                },
                trailingContent = { IconButton(onClick = { pending = t }) { Icon(Icons.Filled.Delete, "Borrar") } },
            )
        }
    }
    pending?.let { t ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("¿Borrar operación?") },
            text = { Text("${typeLabel(t.type)} ${t.symbol ?: ""} del ${t.date}") },
            confirmButton = { TextButton(onClick = { onDelete(t.id); pending = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } },
        )
    }
}

fun typeLabel(type: String) = when (type) {
    "BUY" -> "Compra"; "SELL" -> "Venta"; "SHORT" -> "Venta en corto"; "COVER" -> "Cierre de corto"; "DIVIDEND" -> "Dividendo"; "DEPOSIT" -> "Ingreso"; "WITHDRAWAL" -> "Retirada"
    "FEE" -> "Comisión"; "TAX" -> "Impuesto"; "INTEREST" -> "Interés"; "SPLIT" -> "Split"; else -> type
}

@Composable
private fun PlanVsActual(plan: com.portafolios.core.risk.PortfolioPlan, actual: Map<com.portafolios.core.risk.AssetClass, Double>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tu plan (${plan.profile.label}) frente a la cartera real", style = MaterialTheme.typography.titleSmall)
            com.portafolios.core.risk.AssetClass.entries.forEach { cls ->
                val target = plan.targets[cls] ?: 0.0
                val now = actual[cls] ?: 0.0
                val off = kotlin.math.abs(now - target) > plan.rebalanceBandPct
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(cls.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "%.1f %% / %.0f %%".format(now, target), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold, color = if (off) com.portafolios.app.ui.theme.LossColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    LinearProgressIndicator(progress = { (now / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (actual.isEmpty()) Text("Actualiza precios para comparar.", style = MaterialTheme.typography.bodySmall)
            else Text("Real / objetivo. En rojo, fuera de tu margen de ±${plan.rebalanceBandPct.toInt()} puntos.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** "17:42" today, "25/11 22:00" otherwise. */
fun priceTime(millis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val f = if (t.toLocalDate() == today) "HH:mm" else "dd/MM HH:mm"
    return t.format(java.time.format.DateTimeFormatter.ofPattern(f))
}
