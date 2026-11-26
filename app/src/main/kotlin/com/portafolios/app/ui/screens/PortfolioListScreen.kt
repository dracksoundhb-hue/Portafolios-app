package com.portafolios.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portafolios.app.AppContainer
import com.portafolios.app.data.PortfolioEntity
import com.portafolios.app.ui.*
import com.portafolios.core.calc.PortfolioCalculator
import com.portafolios.core.calc.PortfolioSummary
import com.portafolios.core.model.Broker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PortfolioRow(val portfolio: PortfolioEntity, val summary: PortfolioSummary)

class PortfolioListViewModel(private val c: AppContainer) : ViewModel() {
    val rows: StateFlow<List<PortfolioRow>?> = combine(
        c.portfolios.portfolios(), c.portfolios.transactionsByPortfolio(), c.market.quotes(),
    ) { ps, txs, quotes ->
        ps.map { PortfolioRow(it, PortfolioCalculator.summary(txs[it.id].orEmpty(), quotes)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioListScreen(c: AppContainer, onOpen: (Long) -> Unit, onSettings: () -> Unit, onAlerts: () -> Unit, onCreate: () -> Unit) {
    val vm: PortfolioListViewModel = viewModel { PortfolioListViewModel(c) }
    val rows by vm.rows.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis portafolios") },
                actions = {
                    IconButton(onClick = onAlerts) { Icon(Icons.Filled.Notifications, "Alertas") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ajustes") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nuevo portafolio") })
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            list.isEmpty() -> EmptyState("Crea tu primer portafolio y después importa el extracto de tu broker.", Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.portfolio.id }) { row -> PortfolioCard(row) { onOpen(row.portfolio.id) } }
            }
        }
    }

}

@Composable
private fun PortfolioCard(row: PortfolioRow, onClick: () -> Unit) {
    val s = row.summary
    val cur = s.holdings.firstOrNull()?.position?.currency ?: row.portfolio.baseCurrency
    val neutral = MaterialTheme.colorScheme.onSurface
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(row.portfolio.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val risk = row.portfolio.plan()?.profile?.label
            Text(listOfNotNull(runCatching { Broker.valueOf(row.portfolio.broker).displayName }.getOrDefault(row.portfolio.broker), risk).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Valor", money(s.marketValue, cur), Modifier.weight(1f))
                StatChip("G/P", percent(s.unrealizedPnlPercent), Modifier.weight(1f), pnlColor(s.unrealizedPnl, neutral))
                StatChip("Hoy", money(s.dayChange, cur), Modifier.weight(1f), pnlColor(s.dayChange, neutral))
            }
        }
    }
}

@Composable
fun BrokerPicker(selected: Broker, onSelect: (Broker) -> Unit) {
    OptionPicker("Broker", selected.displayName, Broker.entries.map { it.displayName }) { name ->
        onSelect(Broker.entries.first { it.displayName == name })
    }
}

@Composable
fun OptionPicker(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false }) }
            }
        }
    }
}
