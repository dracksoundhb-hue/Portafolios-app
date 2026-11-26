package com.portafolios.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.portafolios.app.AppContainer
import com.portafolios.app.MainActivity
import com.portafolios.app.PortafoliosApp
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Checks price alerts in the background about every 15 minutes (Android's minimum). */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PortafoliosApp).container
        AlertChecker.check(applicationContext, container, prices = null)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "price_alerts"

        fun createChannel(context: Context) = AlertChecker.createChannel(context)

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

object AlertChecker {
    private const val CHANNEL_ID = "price_alerts_v2"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Alertas de precio", NotificationManager.IMPORTANCE_HIGH))
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Notifies every active alert whose price was reached. [prices] are already-fetched prices (in-app
     * refresh); when null, prices are fetched now (background worker). An alert is only marked as done
     * once its notification could actually be shown.
     */
    suspend fun check(context: Context, container: AppContainer, prices: Map<String, BigDecimal>?): Int {
        if (!canNotify(context)) return 0
        val alerts = container.db.alerts().active()
        var fired = 0
        for ((symbol, group) in alerts.groupBy { it.symbol }) {
            val price = if (prices != null) prices[symbol] ?: continue
            else runCatching { container.market.quoteNow(symbol) }.getOrNull() ?: continue
            for (alert in group) {
                val target = BigDecimal(alert.target)
                val hit = if (alert.condition == "ABOVE") price >= target else price <= target
                if (!hit) continue
                val dir = if (alert.condition == "ABOVE") "ha subido a" else "ha bajado a"
                notify(context, alert.id.toInt(), "$symbol $dir ${price.stripTrailingZeros().toPlainString()}", "Tu alerta estaba en ${target.toPlainString()}")
                container.db.alerts().update(alert.copy(active = false, triggeredAt = System.currentTimeMillis()))
                fired++
            }
        }
        return fired
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
