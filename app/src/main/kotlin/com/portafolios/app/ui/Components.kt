package com.portafolios.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portafolios.app.ui.theme.ChartColors
import com.portafolios.core.analysis.Slice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás") } },
        actions = actions,
    )
}

@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Donut chart with a legend, used for sector and country allocation. */
@Composable
fun DonutChart(title: String, slices: List<Slice>, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (slices.isEmpty()) {
                Text("Sin datos todavía. Actualiza precios para clasificar tus posiciones.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val top = slices.take(ChartColors.size - 1).toMutableList()
            if (slices.size > top.size) {
                val rest = slices.drop(top.size).fold(java.math.BigDecimal.ZERO) { a, s -> a + s.percent }
                top += Slice("Otros", rest)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(120.dp)) {
                    val stroke = size.minDimension * 0.18f
                    val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
                    val offset = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
                    var start = -90f
                    top.forEachIndexed { i, s ->
                        val sweep = s.percent.toFloat() * 3.6f
                        drawArc(ChartColors[i % ChartColors.size], start, sweep, useCenter = false, topLeft = offset, size = arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    top.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(Modifier.size(10.dp).background(ChartColors[i % ChartColors.size], CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("${s.label}  ${percent(s.percent, signed = false)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
