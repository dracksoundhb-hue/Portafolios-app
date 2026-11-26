package com.portafolios.app.backup

import androidx.room.withTransaction
import com.portafolios.app.data.AlertEntity
import com.portafolios.app.data.AppDatabase
import com.portafolios.app.data.PortfolioEntity
import com.portafolios.app.data.TransactionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup file chosen by the user: PBKDF2-HMAC-SHA256 (310 000 iterations) derives an
 * AES-256-GCM key from a password only the user knows. Format: "PFB1" | salt(16) | iv(12) | ciphertext.
 */
class BackupManager(private val db: AppDatabase) {
    @Serializable data class P(val id: Long, val name: String, val broker: String, val currency: String, val createdAt: Long)
    @Serializable data class T(
        val portfolioId: Long, val date: String, val type: String, val symbol: String?, val isin: String?, val name: String?,
        val quantity: String, val price: String, val fee: String, val amount: String, val currency: String,
        val brokerRef: String?, val fingerprint: String,
    )
    @Serializable data class A(val symbol: String, val condition: String, val target: String, val active: Boolean)
    @Serializable data class Backup(val version: Int = 1, val portfolios: List<P>, val transactions: List<T>, val alerts: List<A>)

    class WrongPasswordException : Exception("Contraseña incorrecta o archivo dañado")

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun export(password: CharArray): ByteArray {
        val backup = Backup(
            portfolios = db.portfolios().all().map { P(it.id, it.name, it.broker, it.baseCurrency, it.createdAt) },
            transactions = db.transactions().all().map {
                T(it.portfolioId, it.date, it.type, it.symbol, it.isin, it.name, it.quantity, it.price, it.fee, it.amount, it.currency, it.brokerRef, it.fingerprint)
            },
            alerts = db.alerts().active().map { A(it.symbol, it.condition, it.target, it.active) },
        )
        val plain = json.encodeToString(Backup.serializer(), backup).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt))
        return MAGIC + salt + cipher.iv + cipher.doFinal(plain)
    }

    /** Restores a backup as new portfolios (existing data is kept). Returns portfolios restored. */
    suspend fun restore(data: ByteArray, password: CharArray): Int {
        if (data.size < 4 + 16 + 12 + 16 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) throw WrongPasswordException()
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
                doFinal(data, 32, data.size - 32)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException()
        }
        val backup = json.decodeFromString(Backup.serializer(), String(plain, Charsets.UTF_8))
        db.withTransaction {
            val idMap = backup.portfolios.associate { p ->
                p.id to db.portfolios().insert(PortfolioEntity(name = p.name, broker = p.broker, baseCurrency = p.currency, createdAt = p.createdAt))
            }
            db.transactions().insertAll(backup.transactions.mapNotNull { t ->
                val pid = idMap[t.portfolioId] ?: return@mapNotNull null
                TransactionEntity(
                    portfolioId = pid, date = t.date, type = t.type, symbol = t.symbol, isin = t.isin, name = t.name,
                    quantity = t.quantity, price = t.price, fee = t.fee, amount = t.amount, currency = t.currency,
                    brokerRef = t.brokerRef, fingerprint = t.fingerprint, importId = null,
                )
            })
            backup.alerts.forEach { db.alerts().insert(AlertEntity(symbol = it.symbol, condition = it.condition, target = it.target, active = it.active)) }
        }
        return backup.portfolios.size
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    companion object {
        private val MAGIC = "PFB1".toByteArray()
    }
}
