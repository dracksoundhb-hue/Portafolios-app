package com.portafolios.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portafolios.app.AppContainer
import com.portafolios.app.alerts.PriceAlertWorker
import com.portafolios.app.data.AlertEntity
import com.portafolios.app.ui.BackTopBar
import com.portafolios.app.ui.EmptyState
import com.portafolios.core.importer.Numbers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsViewModel(private val c: AppContainer) : ViewModel() {
    val alerts: StateFlow<List<AlertEntity>> = c.db.alerts().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hasKey get() = c.market.hasApiKey

    fun add(symbol: String, above: Boolean, target: java.math.BigDecimal) = viewModelScope.launch {
        c.db.alerts().insert(AlertEntity(symbol = symbol.trim().uppercase(), condition = if (above) "ABOVE" else "BELOW", target = target.toPlainString()))
    }

    fun delete(id: Long) = viewModelScope.launch { c.db.alerts().delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(c: AppContainer, onBack: () -> Unit) {
    val vm: AlertsViewModel = viewModel { AlertsViewModel(c) }
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        topBar = { BackTopBar("Alertas de precio", onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Nueva alerta") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            if (!vm.hasKey) item {
                Card { Text("Añade tu clave de Finnhub en Ajustes para que las alertas puedan consultar precios.", Modifier.padding(12.dp)) }
            }
            if (alerts.isEmpty()) item { EmptyState("Sin alertas. Se comprueban cada 30 minutos aproximadamente.") }
            items(alerts, key = { it.id }) { a ->
                ListItem(
                    headlineContent = { Text("${a.symbol} ${if (a.condition == "ABOVE") "≥" else "≤"} ${a.target}") },
                    supportingContent = { Text(if (a.active) "Activa" else "Disparada") },
                    trailingContent = { IconButton(onClick = { vm.delete(a.id) }) { Icon(Icons.Filled.Delete, "Borrar") } },
                )
            }
        }
    }

    if (adding) {
        var symbol by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var above by remember { mutableStateOf(false) }
        val value = Numbers.parse(target)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Nueva alerta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(symbol, { symbol = it.take(20) }, label = { Text("Símbolo (ej. AAPL)") }, singleLine = true)
                    OutlinedTextField(target, { target = it.take(20) }, label = { Text("Precio") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Row {
                        FilterChip(selected = !above, onClick = { above = false }, label = { Text("Baja de") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = above, onClick = { above = true }, label = { Text("Sube de") })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = symbol.isNotBlank() && value != null && value.signum() > 0, onClick = {
                    vm.add(symbol, above, value!!)
                    PriceAlertWorker.schedule(context)
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    adding = false
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancelar") } },
        )
    }
}
