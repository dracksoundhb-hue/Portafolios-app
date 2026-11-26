package com.portafolios.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portafolios.app.AppContainer
import com.portafolios.app.alerts.PriceAlertWorker
import com.portafolios.app.data.AlertEntity
import com.portafolios.app.data.TransactionEntity
import com.portafolios.app.ui.*
import com.portafolios.core.calc.Holding
import com.portafolios.core.calc.PortfolioCalculator
import com.portafolios.core.importer.Numbers
import com.portafolios.core.model.Position
import com.portafolios.core.model.Quote
import com.portafolios.core.model.SecurityProfile
import com.portafolios.core.risk.AssetClassifier
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class PositionState(
    val position: Position?,
    val quote: Quote?,
    val profile: SecurityProfile?,
    val weight: BigDecimal?,
    val transactions: List<TransactionEntity>,
    val alerts: List<AlertEntity>,
)

class PositionViewModel(private val c: AppContainer, portfolioId: Long, val symbol: String) : ViewModel() {
    val state: StateFlow<PositionState?> = combine(
        c.portfolios.transactions(portfolioId), c.market.quotes(), c.market.profiles(), c.db.alerts().observeAll(),
    ) { txs, quotes, profiles, alerts ->
        val mine = txs.filter { (it.symbol?.uppercase() ?: it.isin?.uppercase()) == symbol }
        val summary = PortfolioCalculator.summary(txs.map { it.toModel() }, quotes)
        PositionState(
            position = PortfolioCalculator.positions(mine.map { it.toModel() }).firstOrNull(),
            quote = quotes[symbol], profile = profiles[symbol], weight = summary.weights[symbol],
            transactions = mine, alerts = alerts.filter { it.symbol == symbol },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun addAlert(above: Boolean, price: BigDecimal) = viewModelScope.launch {
        c.db.alerts().insert(AlertEntity(symbol = symbol, condition = if (above) "ABOVE" else "BELOW", target = price.toPlainString()))
    }

    fun deleteAlert(id: Long) = viewModelScope.launch { c.db.alerts().delete(id) }
}

@Composable
fun PositionScreen(c: AppContainer, portfolioId: Long, symbol: String, onBack: () -> Unit) {
    val vm: PositionViewModel = viewModel(key = "pos-$portfolioId-$symbol") { PositionViewModel(c, portfolioId, symbol) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = objetivo, false = stop

    Scaffold(topBar = { BackTopBar(s?.profile?.name ?: symbol, onBack) }) { padding ->
        val st = s ?: return@Scaffold
        val p = st.position
        val h = p?.let { Holding(it, st.quote) }
        val cur = p?.currency ?: "USD"
        val neutral = MaterialTheme.colorScheme.onSurface
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(symbol, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(money(st.quote?.price, cur), fontSize = 40.sp, fontWeight = FontWeight.Light)
                    Text(
                        if (st.quote == null) "Sin precio. Pulsa actualizar en el portafolio." else "${money(st.quote.change, cur)} (${percent(st.quote.changePercent)}) hoy",
                        color = pnlColor(st.quote?.change, MaterialTheme.colorScheme.onSurfaceVariant), textAlign = TextAlign.Center,
                    )
                }
            }
            if (p == null) item { EmptyState("Sin operaciones de este valor.") }
            else item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val side = if (p.isShort) "En corto" else "En largo"
                        Text(
                            if (p.isOpen) "$side ${money(p.costBasis.abs(), cur)} (${number(p.quantity.abs(), 4)} ${unit(symbol)})" else "Posición cerrada",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Row {
                            Field(money(p.averageCost, cur), if (p.isShort) "Precio medio de venta" else "Precio medio de compra", Modifier.weight(1f))
                            Field(percent(h?.unrealizedPnlPercent), "Retorno de la inversión", Modifier.weight(1f), pnlColor(h?.unrealizedPnl, neutral))
                        }
                        Row {
                            Field(money(p.realizedPnl, cur), "G/P realizada", Modifier.weight(1f), pnlColor(p.realizedPnl, neutral))
                            Field(money(h?.unrealizedPnl, cur), "G/P no realizada", Modifier.weight(1f), pnlColor(h?.unrealizedPnl, neutral))
                        }
                        Row {
                            Field(money(h?.marketValue?.abs(), cur), "Valor actual", Modifier.weight(1f))
                            Field(percent(st.weight, signed = false), "Peso en el portafolio", Modifier.weight(1f))
                        }
                        if (p.dividends.signum() != 0) Field(money(p.dividends, cur), "Dividendos netos")
                        Text(
                            listOfNotNull(AssetClassifier.classify(symbol, st.profile?.sector).label, st.profile?.sector, st.profile?.country).distinct().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (p != null && p.isOpen) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            TextButton(onClick = { editing = false }, Modifier.weight(1f)) { Text("+ Stop") }
                            TextButton(onClick = { editing = true }, Modifier.weight(1f)) { Text("+ Objetivo") }
                        }
                        val targetAbove = !p.isShort
                        st.alerts.filter { it.active }.forEach { a ->
                            val price = BigDecimal(a.target)
                            val isTarget = (a.condition == "ABOVE") == targetAbove
                            val pnl = estimate(p, price)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${if (isTarget) "Objetivo" else "Stop"} a ${money(price, cur)}", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${if (pnl.signum() >= 0) "Ganancia" else "Pérdida"} aprox. ${money(pnl, cur)}",
                                        style = MaterialTheme.typography.bodySmall, color = pnlColor(pnl, neutral),
                                    )
                                }
                                IconButton(onClick = { vm.deleteAlert(a.id) }) { Icon(Icons.Filled.Close, "Quitar") }
                            }
                        }
                        Text(
                            "Son avisos en tu móvil, no órdenes en tu broker. Te llega una notificación cuando el precio llega ahí.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (st.transactions.isNotEmpty()) {
                item { Text("Operaciones", style = MaterialTheme.typography.titleMedium) }
                items(st.transactions, key = { it.id }) { t ->
                    ListItem(
                        headlineContent = { Text(typeLabel(t.type)) },
                        supportingContent = { Text(listOfNotNull(t.date, t.time?.take(5)).joinToString(" ")) },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${number(BigDecimal(t.quantity), 4)} × ${money(BigDecimal(t.price), t.currency)}", style = MaterialTheme.typography.bodySmall)
                                Text(money(BigDecimal(t.amount), t.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }

        editing?.let { isTarget ->
            val pos = p ?: return@let
            var text by remember { mutableStateOf("") }
            val price = Numbers.parse(text)
            val above = isTarget != pos.isShort
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(if (isTarget) "Precio objetivo" else "Precio de stop") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            text, { text = it.take(15) }, label = { Text("Precio") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (price != null) {
                            val pnl = estimate(pos, price)
                            Text("${if (pnl.signum() >= 0) "Ganancia" else "Pérdida"} aprox. ${money(pnl, cur)}", color = pnlColor(pnl, MaterialTheme.colorScheme.onSurface))
                        }
                        Text("Te avisaremos cuando el precio ${if (above) "suba a" else "baje a"} ese nivel.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(enabled = price != null && price.signum() > 0, onClick = {
                        vm.addAlert(above, price!!)
                        PriceAlertWorker.schedule(context)
                        editing = null
                    }) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
            )
        }
    }
}

/** Unrealized gain or loss of the open position if the price reached [price]. */
private fun estimate(p: Position, price: BigDecimal): BigDecimal = p.quantity * (price - p.averageCost)

private fun unit(symbol: String) = if (symbol.contains('/')) symbol.substringBefore('/') else "acciones"

@Composable
private fun Field(value: String, label: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
