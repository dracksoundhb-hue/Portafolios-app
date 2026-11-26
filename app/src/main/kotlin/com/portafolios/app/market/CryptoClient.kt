package com.portafolios.app.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real-time crypto prices straight from the exchanges' public APIs (no key, no account):
 * Binance first, Coinbase Exchange as fallback.
 */
class CryptoClient {
    data class CryptoQuote(val price: Double, val change: Double, val changePercent: Double, val timeMillis: Long)

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable private data class BinanceTicker(val lastPrice: String = "0", val priceChange: String = "0", val priceChangePercent: String = "0", val closeTime: Long = 0)
    @Serializable private data class CoinbaseStats(val open: String = "0", val last: String = "0")

    suspend fun quote(base: String): CryptoQuote = withContext(Dispatchers.IO) {
        val b = base.uppercase().filter { it.isLetterOrDigit() }
        runCatching {
            val t = json.decodeFromString(BinanceTicker.serializer(), get("https://api.binance.com/api/v3/ticker/24hr?symbol=${b}USDT"))
            CryptoQuote(t.lastPrice.toDouble(), t.priceChange.toDouble(), t.priceChangePercent.toDouble(), t.closeTime.takeIf { it > 0 } ?: System.currentTimeMillis())
        }.recoverCatching {
            val s = json.decodeFromString(CoinbaseStats.serializer(), get("https://api.exchange.coinbase.com/products/$b-USD/stats"))
            val last = s.last.toDouble(); val open = s.open.toDouble()
            CryptoQuote(last, last - open, if (open > 0) (last - open) / open * 100 else 0.0, System.currentTimeMillis())
        }.getOrThrow().also { if (it.price <= 0) throw IOException("Sin precio para $b") }
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Portafolios-Android").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }
}
