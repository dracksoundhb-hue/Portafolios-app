package com.portafolios.app.ui

import androidx.compose.ui.graphics.Color
import com.portafolios.app.ui.theme.GainColor
import com.portafolios.app.ui.theme.LossColor
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")

fun money(v: BigDecimal?, currency: String = "USD"): String {
    if (v == null) return "—"
    val f = NumberFormat.getCurrencyInstance(es)
    runCatching { f.currency = Currency.getInstance(currency) }
    return f.format(v)
}

fun number(v: BigDecimal?, decimals: Int = 2): String {
    if (v == null) return "—"
    val f = NumberFormat.getNumberInstance(es).apply { maximumFractionDigits = decimals; minimumFractionDigits = 0 }
    return f.format(v)
}

fun percent(v: BigDecimal?, signed: Boolean = true): String {
    if (v == null) return "—"
    val s = number(v.setScale(2, RoundingMode.HALF_UP), 2) + " %"
    return if (signed && v.signum() > 0) "+$s" else s
}

fun pnlColor(v: BigDecimal?, neutral: Color): Color = when {
    v == null || v.signum() == 0 -> neutral
    v.signum() > 0 -> GainColor
    else -> LossColor
}
