package com.portafolios.app.data

import androidx.room.withTransaction
import com.portafolios.core.importer.ImportPreview
import com.portafolios.core.model.Broker
import com.portafolios.core.model.Transaction
import com.portafolios.core.risk.PortfolioPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PortfolioRepository(private val db: AppDatabase) {
    fun portfolios(): Flow<List<PortfolioEntity>> = db.portfolios().observeAll()
    fun portfolio(id: Long): Flow<PortfolioEntity?> = db.portfolios().observe(id)

    fun transactions(portfolioId: Long): Flow<List<TransactionEntity>> = db.transactions().observeFor(portfolioId)

    /** All transactions grouped by portfolio, as domain models. */
    fun transactionsByPortfolio(): Flow<Map<Long, List<Transaction>>> =
        db.transactions().observeAll().map { list -> list.groupBy({ it.portfolioId }, { it.toModel() }) }

    fun imports(portfolioId: Long): Flow<List<ImportEntity>> = db.imports().observeFor(portfolioId)

    suspend fun createPortfolio(name: String, broker: Broker, currency: String, plan: PortfolioPlan?): Long {
        val base = PortfolioEntity(name = name.trim(), broker = broker.name, baseCurrency = currency)
        return db.portfolios().insert(if (plan != null) base.withPlan(plan) else base)
    }

    suspend fun updatePortfolio(p: PortfolioEntity) = db.portfolios().update(p)

    suspend fun deletePortfolio(id: Long) = db.portfolios().delete(id)

    suspend fun fingerprints(portfolioId: Long): Set<String> = db.transactions().fingerprints(portfolioId).toSet()

    /** Saves the new rows of a confirmed preview and records the import. Returns rows added. */
    suspend fun saveImport(portfolioId: Long, fileName: String, preview: ImportPreview): Int = db.withTransaction {
        val importId = db.imports().insert(
            ImportEntity(portfolioId = portfolioId, broker = preview.broker.name, fileName = fileName, importedAt = System.currentTimeMillis(), added = 0, duplicates = 0),
        )
        val ids = db.transactions().insertAll(preview.newRows.map { TransactionEntity.from(portfolioId, it.transaction, it.fingerprint, importId) })
        val added = ids.count { it != -1L }
        db.imports().setCounts(importId, added, preview.rows.size - added)
        added
    }

    /** Removes an import and every transaction it added. */
    suspend fun undoImport(importId: Long) = db.withTransaction {
        db.transactions().deleteImport(importId)
        db.imports().delete(importId)
    }

    suspend fun addManual(portfolioId: Long, tx: Transaction) {
        val existing = fingerprints(portfolioId)
        var occurrence = 0
        while (tx.fingerprint(occurrence) in existing) occurrence++
        db.transactions().insertAll(listOf(TransactionEntity.from(portfolioId, tx, tx.fingerprint(occurrence), null)))
    }

    suspend fun deleteTransaction(id: Long) = db.transactions().delete(id)
}
