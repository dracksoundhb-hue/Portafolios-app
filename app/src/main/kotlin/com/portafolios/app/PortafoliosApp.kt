package com.portafolios.app

import android.app.Application
import com.portafolios.app.alerts.PriceAlertWorker
import com.portafolios.app.data.AppDatabase
import com.portafolios.app.data.PortfolioRepository
import com.portafolios.app.market.FinnhubClient
import com.portafolios.app.market.MarketRepository
import com.portafolios.app.security.SecretStore
import com.portafolios.app.security.Settings

class PortafoliosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PriceAlertWorker.createChannel(this)
    }
}

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(app: Application) {
    val secrets = SecretStore(app)
    val settings = Settings(app)
    val db: AppDatabase by lazy { AppDatabase.open(app, secrets.databasePassphrase()) }
    val portfolios by lazy { PortfolioRepository(db) }
    val market by lazy { MarketRepository(db, FinnhubClient { secrets.getString(SecretStore.FINNHUB_API_KEY) }) }
}
