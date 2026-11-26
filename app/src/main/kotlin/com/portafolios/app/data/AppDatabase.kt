package com.portafolios.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [PortfolioEntity::class, TransactionEntity::class, ImportEntity::class, QuoteEntity::class, ProfileEntity::class, AlertEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun portfolios(): PortfolioDao
    abstract fun transactions(): TransactionDao
    abstract fun imports(): ImportDao
    abstract fun market(): MarketDao
    abstract fun alerts(): AlertDao

    companion object {
        /** Opens the SQLCipher-encrypted database (AES-256) with the Keystore-protected passphrase. */
        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "portafolios.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
