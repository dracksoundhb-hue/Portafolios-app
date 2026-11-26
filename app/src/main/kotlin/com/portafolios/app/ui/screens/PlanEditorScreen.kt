package com.portafolios.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portafolios.app.AppContainer
import com.portafolios.app.data.PortfolioEntity
import com.portafolios.app.ui.BackTopBar
import com.portafolios.app.ui.theme.LossColor
import com.portafolios.core.importer.Numbers
import com.portafolios.core.model.Broker
import com.portafolios.core.risk.AssetClass
import com.portafolios.core.risk.PortfolioPlan
import com.portafolios.core.risk.RiskModel
import com.portafolios.core.risk.RiskProfile
import kotlinx.coroutines.launch

/**
 * Creates a portfolio ([portfolioId] null) or edits the risk plan of an existing one.
 * Presets fill the percentages with the recommended allocation; the user may change any of them and
 * the recommendation stays visible under each field.
 */
@Composable
fun PlanEditorScreen(c: AppContainer, portfolioId: Long?, onDone: (Long) -> Unit, onBack: () -> Unit) {
    val existing: PortfolioEntity? = portfolioId?.let { id ->
        c.portfolios.portfolio(id).collectAsStateWithLifecycle(null).value ?: return
    }
    val scope = rememberCoroutineScope()
    val initialPlan = remember(existing) { existing?.plan() ?: PortfolioPlan.recommended(RiskProfile.MEDIUM) }

    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var broker by remember(existing) { mutableStateOf(existing?.broker?.let { runCatching { Broker.valueOf(it) }.getOrNull() } ?: Broker.QUANTFURY) }
    var currency by remember(existing) { mutableStateOf(existing?.baseCurrency ?: "USD") }
    var profile by remember(existing) { mutableStateOf(initialPlan.profile) }
    val fields = remember(existing) { mutableStateMapOf<AssetClass, String>().apply { initialPlan.targets.forEach { (k, v) -> put(k, fmt(v)) } } }
    var maxPos by remember(existing) { mutableStateOf(fmt(initialPlan.maxPositionPct)) }
    var band by remember(existing) { mutableStateOf(fmt(initialPlan.rebalanceBandPct)) }
    var horizon by remember(existing) { mutableStateOf(initialPlan.horizonYears.toString()) }
    var showMethod by remember { mutableStateOf(false) }

    fun choose(p: RiskProfile) {
        profile = p
        if (p != RiskProfile.CUSTOM) {
            p.targets.forEach { (k, v) -> fields[k] = v.toString() }
            maxPos = p.maxPositionPct.toString(); band = p.rebalanceBandPct.toString(); horizon = p.horizonYears.toString()
        }
    }

    val targets = AssetClass.entries.associateWith { Numbers.parse(fields[it])?.toDouble()?.coerceAtLeast(0.0) ?: 0.0 }
    val total = targets.values.sum()
    val plan = PortfolioPlan(
        profile, targets, Numbers.parse(maxPos)?.toDouble() ?: profile.maxPositionPct.toDouble(),
        Numbers.parse(band)?.toDouble() ?: profile.rebalanceBandPct.toDouble(), horizon.toIntOrNull()?.coerceIn(1, 50) ?: profile.horizonYears,
    )
    val metrics = if (total > 0) RiskModel.metrics(targets, plan.horizonYears) else null
    val totalOk = kotlin.math.abs(total - 100) < 0.5
    val canSave = totalOk && (existing != null || name.isNotBlank())

    Scaffold(topBar = { BackTopBar(if (existing == null) "Nuevo portafolio" else "Perfil de riesgo", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (existing == null) {
                BrokerPicker(broker) { broker = it }
                OptionPicker("Divisa base", currency, listOf("USD", "EUR", "GBP", "CHF", "MXN")) { currency = it }
            }

            Text("Nivel de riesgo", style = MaterialTheme.typography.titleMedium)
            listOf(listOf(RiskProfile.LOW, RiskProfile.MEDIUM), listOf(RiskProfile.HIGH, RiskProfile.CUSTOM)).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { p ->
                        val label = if (p == RiskProfile.LOW) "Leve" else if (p == RiskProfile.MEDIUM) "Medio" else if (p == RiskProfile.HIGH) "Alto" else "Personalizado"
                        if (profile == p) Button(onClick = { choose(p) }, Modifier.weight(1f)) { Text(label) }
                        else OutlinedButton(onClick = { choose(p) }, Modifier.weight(1f)) { Text(label) }
                    }
                }
            }
            Text(profile.description, style = MaterialTheme.typography.bodySmall)

            Text("¿Cuánto prevés invertir en cada tipo de activo?", style = MaterialTheme.typography.titleMedium)
            AssetClass.entries.forEach { cls ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cls.label, style = MaterialTheme.typography.bodyMedium)
                        if (profile != RiskProfile.CUSTOM) {
                            Text(
                                "Recomendado ${profile.targets.getValue(cls)} %", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = fields[cls].orEmpty(), onValueChange = { fields[cls] = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5) },
                        suffix = { Text("%") }, singleLine = true, modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
            Text(
                "Total: ${fmt(total)} %" + if (!totalOk) " (debe sumar 100 %)" else "",
                fontWeight = FontWeight.Bold, color = if (totalOk) MaterialTheme.colorScheme.onSurface else LossColor,
            )

            if (metrics != null) RiskCard(plan, metrics)

            Text("Límites", style = MaterialTheme.typography.titleMedium)
            if (profile == RiskProfile.CUSTOM) {
                NumberField("Máximo en una sola posición", maxPos, "%") { maxPos = it }
                NumberField("Reequilibrar si un tipo se desvía más de", band, "puntos") { band = it }
                NumberField("Horizonte de inversión", horizon, "años") { horizon = it }
            } else {
                Text(
                    "Máximo ${profile.maxPositionPct} % en una sola posición · reequilibrar si un tipo se desvía más de ${profile.rebalanceBandPct} puntos · horizonte mínimo ${profile.horizonYears} años. " +
                        "Elige Personalizado para cambiarlos.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                if (showMethod) "Ocultar cómo se calcula" else "¿Cómo se calcula?",
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showMethod = !showMethod },
            )
            if (showMethod) Text(METHOD, style = MaterialTheme.typography.bodySmall)

            Button(
                enabled = canSave, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        val id = if (existing == null) c.portfolios.createPortfolio(name, broker, currency, plan)
                        else { c.portfolios.updatePortfolio(existing.copy(name = name.trim().ifEmpty { existing.name }).withPlan(plan)); existing.id }
                        onDone(id)
                    }
                },
            ) { Text(if (existing == null) "Crear portafolio" else "Guardar") }
            Text("Estimaciones estadísticas a largo plazo, no garantías ni asesoramiento financiero.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RiskCard(plan: PortfolioPlan, m: com.portafolios.core.risk.RiskMetrics) {
    val crypto = m.riskContribution.getValue(AssetClass.CRYPTO)
    val budget = plan.profile.cryptoRiskBudgetPct
    val equivalent = RiskProfile.equivalentTo(m.volatility)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Riesgo estimado de este reparto", style = MaterialTheme.typography.titleSmall)
            Metric("Oscilación anual típica (volatilidad)", "±${fmt1(m.volatility)} %")
            Metric("Rentabilidad anual esperada", "${fmt1(m.expectedReturn)} %")
            Metric("Un año malo (1 de cada 20)", "−${fmt1(m.valueAtRisk95)} % o más")
            Metric("Media de esos años malos", "−${fmt1(m.expectedShortfall95)} %")
            Metric("Probabilidad de perder en 1 año", "${fmt0(m.probLoss1y)} %")
            Metric("Probabilidad de perder a ${plan.horizonYears} años", "${fmt0(m.probLossHorizon)} %")
            Metric("Riesgo que aporta la cripto", "${fmt1(crypto)} % del total")
            if (plan.profile != RiskProfile.CUSTOM && crypto > budget + 0.5) {
                Text(
                    "La cripto supera el límite de riesgo de este perfil ($budget % del total). Con esta cantidad, tu cartera se comporta más como riesgo ${equivalent.label.removePrefix("Riesgo ").lowercase()}.",
                    color = LossColor, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plan.profile == RiskProfile.CUSTOM || equivalent != plan.profile) {
                Text("Equivale a: ${equivalent.label}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NumberField(label: String, value: String, suffix: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(5)) }, label = { Text(label) },
        suffix = { Text(suffix) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)
private fun fmt1(v: Double) = "%.1f".format(java.util.Locale.forLanguageTag("es-ES"), v)
private fun fmt0(v: Double) = "%.0f".format(v)

private const val METHOD = """Cada perfil parte de un reparto clásico entre acciones, bonos, liquidez, oro e inmobiliario, como los modelos conservador, equilibrado y de crecimiento de las grandes gestoras.

La criptomoneda se dimensiona por presupuesto de riesgo: como oscila unas 4 veces más que la bolsa, se limita a que aporte como máximo el 5 % (leve), 10 % (medio) o 15 % (alto) del riesgo total de la cartera. Eso da un 1 %, 3 % y 6 %, en línea con lo que proponen BlackRock (1-2 %) y Morgan Stanley (0-4 %).

Los números de riesgo salen de la teoría de carteras (Markowitz): volatilidades y correlaciones a largo plazo de cada tipo de activo. El "año malo" es el valor en riesgo al 95 %, y la probabilidad de perder usa una distribución log-normal de rentabilidades. Son estimaciones: los mercados reales tienen caídas más bruscas de lo que predice el modelo."""
