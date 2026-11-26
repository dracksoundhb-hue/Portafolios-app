package com.portafolios.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios ORDER BY createdAt")
    fun observeAll(): Flow<List<PortfolioEntity>>

    @Query("SELECT * FROM portfolios WHERE id = :id")
    fun observe(id: Long): Flow<PortfolioEntity?>

    @Query("SELECT * FROM portfolios")
    suspend fun all(): List<PortfolioEntity>

    @Insert
    suspend fun insert(p: PortfolioEntity): Long

    @Update
    suspend fun update(p: PortfolioEntity)

    @Query("DELETE FROM portfolios WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE portfolioId = :portfolioId ORDER BY date DESC, id DESC")
    fun observeFor(portfolioId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY date, id")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT fingerprint FROM transactions WHERE portfolioId = :portfolioId")
    suspend fun fingerprints(portfolioId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<TransactionEntity>): List<Long>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transactions WHERE importId = :importId")
    suspend fun deleteImport(importId: Long)
}

@Dao
interface ImportDao {
    @Query("SELECT * FROM imports WHERE portfolioId = :portfolioId ORDER BY importedAt DESC")
    fun observeFor(portfolioId: Long): Flow<List<ImportEntity>>

    @Insert
    suspend fun insert(i: ImportEntity): Long

    @Query("UPDATE imports SET added = :added, duplicates = :duplicates WHERE id = :id")
    suspend fun setCounts(id: Long, added: Int, duplicates: Int)

    @Query("DELETE FROM imports WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MarketDao {
    @Query("SELECT * FROM quotes")
    fun observeQuotes(): Flow<List<QuoteEntity>>

    @Query("SELECT * FROM profiles")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE `key` = :key")
    suspend fun profile(key: String): ProfileEntity?

    @Upsert
    suspend fun upsertQuote(q: QuoteEntity)

    @Upsert
    suspend fun upsertProfile(p: ProfileEntity)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY active DESC, createdAt DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE active = 1")
    suspend fun active(): List<AlertEntity>

    @Insert
    suspend fun insert(a: AlertEntity): Long

    @Update
    suspend fun update(a: AlertEntity)

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: Long)
}
