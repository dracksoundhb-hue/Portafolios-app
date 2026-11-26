package com.portafolios.app.market

import com.portafolios.app.data.AppDatabase
import com.portafolios.app.data.ProfileEntity
import com.portafolios.app.data.QuoteEntity
import com.portafolios.core.model.Quote
import com.portafolios.core.model.SecurityProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

class MarketRepository(private val db: AppDatabase, private val client: FinnhubClient) {
    val hasApiKey: Boolean get() = client.hasKey

    fun quotes(): Flow<Map<String, Quote>> = db.market().observeQuotes().map { list ->
        list.associate { it.key to Quote(it.key, BigDecimal(it.price), BigDecimal(it.change), BigDecimal(it.changePercent)) }
    }

    fun profiles(): Flow<Map<String, SecurityProfile>> = db.market().observeProfiles().map { list ->
        list.associate { it.key to SecurityProfile(it.key, it.name, it.sector, it.country) }
    }

    /**
     * Refreshes quotes (and profiles older than a week) for the given instrument keys.
     * Returns the keys that failed, with the reason, so the UI can say which ones have no price.
     */
    suspend fun refresh(keys: Collection<String>): Map<String, String> {
        val failures = mutableMapOf<String, String>()
        for (key in keys.distinct()) {
            try {
                val profile = ensureProfile(key)
                val ticker = profile?.ticker ?: key
                val q = client.quote(ticker)
                val price = q.c
                if (price == null || price == 0.0) {
                    failures[key] = "Sin cotización para $ticker"
                } else {
                    db.market().upsertQuote(
                        QuoteEntity(key, price.toBigDecimal().toPlainString(), (q.d ?: 0.0).toBigDecimal().toPlainString(),
                            (q.dp ?: 0.0).toBigDecimal().toPlainString(), System.currentTimeMillis()),
                    )
                }
            } catch (e: FinnhubClient.MissingKeyException) {
                throw e
            } catch (e: Exception) {
                failures[key] = e.message ?: "Error"
            }
            delay(1100) // stay under the free plan's 60 calls/min
        }
        return failures
    }

    suspend fun quoteNow(symbol: String): BigDecimal? {
        val ticker = db.market().profile(symbol)?.ticker ?: symbol
        return client.quote(ticker).c?.takeIf { it > 0 }?.toBigDecimal()
    }

    private suspend fun ensureProfile(key: String): ProfileEntity? {
        val cached = db.market().profile(key)
        CRYPTO.find(key)?.let { m ->
            // Crypto pairs (ETH/USD): price from Binance through Finnhub.
            if (cached != null) return cached
            val entity = ProfileEntity(key, "BINANCE:${m.groupValues[1]}USDT", m.groupValues[1], "Criptomonedas", null, System.currentTimeMillis())
            db.market().upsertProfile(entity)
            return entity
        }
        if (cached != null && System.currentTimeMillis() - cached.updatedAt < WEEK_MS) return cached
        val ticker = if (ISIN.matches(key)) client.search(key).result.firstOrNull()?.symbol ?: return cached else key
        val p = runCatching { client.profile(ticker) }.getOrNull()
        val entity = ProfileEntity(
            key = key, ticker = ticker, name = p?.name, sector = p?.finnhubIndustry?.takeIf { it.isNotBlank() } ?: cached?.sector,
            country = p?.country?.takeIf { it.isNotBlank() } ?: cached?.country, updatedAt = System.currentTimeMillis(),
        )
        db.market().upsertProfile(entity)
        return entity
    }

    companion object {
        private const val WEEK_MS = 7L * 24 * 3600 * 1000
        private val CRYPTO = Regex("^([A-Z0-9]+)/(USD|USDT|USDC)$")
        private val ISIN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
    }
}
