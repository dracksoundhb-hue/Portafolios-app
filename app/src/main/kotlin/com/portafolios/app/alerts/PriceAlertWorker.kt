package com.portafolios.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.portafolios.app.PortafoliosApp
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Checks active price alerts every 30 minutes and notifies the ones that were reached. */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PortafoliosApp).container
        if (!container.market.hasApiKey) return Result.success()
        val alerts = container.db.alerts().active()
        if (alerts.isEmpty()) return Result.success()
        for ((symbol, group) in alerts.groupBy { it.symbol }) {
            val price = runCatching { container.market.quoteNow(symbol) }.getOrNull() ?: continue
            for (alert in group) {
                val target = BigDecimal(alert.target)
                val hit = if (alert.condition == "ABOVE") price >= target else price <= target
                if (hit) {
                    container.db.alerts().update(alert.copy(active = false, triggeredAt = System.currentTimeMillis()))
                    val dir = if (alert.condition == "ABOVE") "ha subido a" else "ha bajado a"
                    notify(alert.id.toInt(), "$symbol $dir ${price.toPlainString()}", "Tu alerta estaba en ${target.toPlainString()}")
                }
            }
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        private const val CHANNEL_ID = "price_alerts"
        private const val WORK_NAME = "price_alerts"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Alertas de precio", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PriceAlertWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
