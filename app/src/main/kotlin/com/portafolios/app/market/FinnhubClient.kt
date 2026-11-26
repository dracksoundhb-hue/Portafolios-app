package com.portafolios.app.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Finnhub market data (https://finnhub.io, free plan: 60 calls/min, US quotes).
 * The API key goes in a header, never in the URL, so it does not end up in logs.
 */
class FinnhubClient(private val apiKey: () -> String?) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable data class QuoteDto(val c: Double? = null, val d: Double? = null, val dp: Double? = null, val t: Long? = null)
    @Serializable data class ProfileDto(val name: String? = null, val finnhubIndustry: String? = null, val country: String? = null, val ticker: String? = null)
    @Serializable data class SearchDto(val result: List<SearchItem> = emptyList())
    @Serializable data class SearchItem(val symbol: String = "", val description: String? = null, val type: String? = null)

    class MissingKeyException : IOException("Falta la clave de Finnhub (Ajustes)")

    val hasKey: Boolean get() = !apiKey().isNullOrBlank()

    private suspend fun get(path: String, vararg params: Pair<String, String>): String = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw MissingKeyException()
        val url = "https://finnhub.io/api/v1/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("X-Finnhub-Token", key).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 429) throw IOException("Límite de peticiones de Finnhub alcanzado, prueba en un minuto")
            if (!resp.isSuccessful) throw IOException("Finnhub respondió ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    suspend fun quote(symbol: String): QuoteDto = json.decodeFromString(QuoteDto.serializer(), get("quote", "symbol" to symbol))
    suspend fun profile(symbol: String): ProfileDto = json.decodeFromString(ProfileDto.serializer(), get("stock/profile2", "symbol" to symbol))
    suspend fun search(query: String): SearchDto = json.decodeFromString(SearchDto.serializer(), get("search", "q" to query))
}
