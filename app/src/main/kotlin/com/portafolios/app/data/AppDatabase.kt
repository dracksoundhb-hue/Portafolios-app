package com.portafolios.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [PortfolioEntity::class, TransactionEntity::class, ImportEntity::class, QuoteEntity::class, ProfileEntity::class, AlertEntity::class, WatchEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun portfolios(): PortfolioDao
    abstract fun transactions(): TransactionDao
    abstract fun imports(): ImportDao
    abstract fun market(): MarketDao
    abstract fun alerts(): AlertDao
    abstract fun watchlist(): WatchDao

    companion object {
        /** v4: watchlist. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `watchlist` (`symbol` TEXT NOT NULL, `name` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`symbol`))")
            }
        }

        /** v3: risk profile and target allocation per portfolio. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE portfolios ADD COLUMN riskProfile TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE portfolios ADD COLUMN targets TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE portfolios ADD COLUMN maxPositionPct REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE portfolios ADD COLUMN rebalanceBandPct REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE portfolios ADD COLUMN horizonYears INTEGER DEFAULT NULL")
            }
        }

        /** v2: time of day and "closes position" flag (needed for brokers like Quantfury). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN time TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE transactions ADD COLUMN closesPosition INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Opens the SQLCipher-encrypted database (AES-256) with the Keystore-protected passphrase. */
        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "portafolios.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
