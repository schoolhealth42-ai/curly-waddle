package com.goldlive.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
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
import java.util.concurrent.TimeUnit

class GoldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(AppPrefs(this).theme)
        Alerts.createChannel(this)
        Alerts.schedule(this)
    }

    companion object {
        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }
}

object Alerts {
    private const val CHANNEL = "gold_alerts"
    private const val WORK = "gold_alert_work"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "تنبيهات سعر الذهب", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "إشعار عند وصول سعر الأونصة إلى الحد الذي تحدده"
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (AppPrefs(ctx).alertsEnabled) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gold)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = AppPrefs(applicationContext)
        if (!prefs.alertsEnabled) return Result.success()
        val price = try {
            Repo.goldSpot().price
        } catch (e: Exception) {
            return Result.retry()
        }
        val txt = Fmt.num(price, 2, prefs.arabicDigits)
        prefs.alertAbove?.let { target ->
            if (price >= target) {
                Alerts.notify(
                    applicationContext, 101, "📈 الذهب تجاوز الحد الأعلى",
                    "سعر الأونصة الآن $txt دولار (الحد: ${Fmt.num(target, 2, prefs.arabicDigits)})"
                )
                prefs.clear("alert_above")
            }
        }
        prefs.alertBelow?.let { target ->
            if (price <= target) {
                Alerts.notify(
                    applicationContext, 102, "📉 الذهب نزل تحت الحد الأدنى",
                    "سعر الأونصة الآن $txt دولار (الحد: ${Fmt.num(target, 2, prefs.arabicDigits)})"
                )
                prefs.clear("alert_below")
            }
        }
        return Result.success()
    }
}
