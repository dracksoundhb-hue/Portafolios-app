package com.portafolios.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.portafolios.app.AppContainer
import com.portafolios.app.backup.BackupManager
import com.portafolios.app.security.SecretStore
import com.portafolios.app.ui.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var apiKey by remember { mutableStateOf(c.secrets.getString(SecretStore.FINNHUB_API_KEY).orEmpty()) }
    var lock by remember { mutableStateOf(c.settings.lockEnabled) }
    var backupPassword by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val backup = remember { BackupManager(c.db) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pwd = backupPassword.toCharArray()
        scope.launch {
            val msg = runCatching {
                val bytes = withContext(Dispatchers.Default) { backup.export(pwd) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
                "Copia guardada"
            }.getOrElse { it.message ?: "Error al guardar" }
            pwd.fill(' ')
            snackbar.showSnackbar(msg)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }

    LaunchedEffect(pendingRestore) {
        val uri = pendingRestore ?: return@LaunchedEffect
        val pwd = backupPassword.toCharArray()
        val msg = runCatching {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            val n = withContext(Dispatchers.Default) { backup.restore(bytes, pwd) }
            "Restaurados $n portafolios"
        }.getOrElse { it.message ?: "Error al restaurar" }
        pwd.fill(' ')
        pendingRestore = null
        snackbar.showSnackbar(msg)
    }

    Scaffold(topBar = { BackTopBar("Ajustes", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Seguridad", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bloqueo con huella o PIN")
                    Text("Se vuelve a pedir tras 1 minuto en segundo plano.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = lock, onCheckedChange = { lock = it; c.settings.lockEnabled = it })
            }
            Text(
                "Tus datos están cifrados en este móvil (AES-256) y no se suben a ningún servidor. La app nunca pide la contraseña de tu broker.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Precios de mercado", style = MaterialTheme.typography.titleMedium)
            Text("Crea una clave gratuita en finnhub.io y pégala aquí. Se guarda cifrada.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                apiKey, { apiKey = it.trim() }, label = { Text("Clave API de Finnhub") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                c.secrets.putString(SecretStore.FINNHUB_API_KEY, apiKey)
                scope.launch { snackbar.showSnackbar("Clave guardada") }
            }) { Text("Guardar clave") }

            HorizontalDivider()
            Text("Copia de seguridad cifrada", style = MaterialTheme.typography.titleMedium)
            Text(
                "Exporta tus portafolios a un archivo protegido con una contraseña que solo tú conoces. Si la olvidas, la copia no se puede abrir.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                backupPassword, { backupPassword = it }, label = { Text("Contraseña de la copia (mín. 10 caracteres)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("portafolios-backup.pfb") }, enabled = backupPassword.length >= 10) { Text("Exportar") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }, enabled = backupPassword.length >= 10) { Text("Restaurar") }
            }
        }
    }
}
