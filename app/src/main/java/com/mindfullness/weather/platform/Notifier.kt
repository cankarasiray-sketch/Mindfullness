package com.mindfullness.weather.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.mindfullness.weather.MainActivity
import com.mindfullness.weather.R
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.ui.Palette
import java.time.LocalDateTime

object Notifier {
    private const val CHANNEL_ALERTS = "weather_alerts"
    private const val CHANNEL_DAILY = "daily_summary"
    private const val MORNING_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Hava uyarıları", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Yağış, fırtına, don, sis ve aşırı sıcak gibi önemli hava olayları"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "Sabah özeti", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Günün hava durumu ve yanınıza almanız gerekenler"
            },
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return hasPermission(context) && manager.areNotificationsEnabled()
    }

    fun showAlert(context: Context, place: Place, alert: WeatherAlert, now: LocalDateTime) {
        if (!canNotify(context)) return
        val marker = when (alert.severity) {
            Severity.RED -> "🔴"
            Severity.ORANGE -> "🟠"
            Severity.YELLOW -> "🟡"
            Severity.INFO -> "🔵"
        }
        val body = buildString {
            append(alert.whenText(now)).append(" · ").append(alert.detail)
            alert.advice.take(2).forEach { append("\n• ").append(it) }
        }
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setColor(Palette.severity(alert.severity))
            .setContentTitle("$marker ${alert.title} · ${place.name}")
            .setContentText("${alert.whenText(now)} · ${alert.detail}")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        notify(context, alert.key.hashCode(), notification)
    }

    fun showMorningSummary(context: Context, place: Place, text: String, importantAlerts: Int) {
        if (!canNotify(context)) return
        val title = buildString {
            append("Günaydın · ").append(place.name)
            if (importantAlerts > 0) append(" · $importantAlerts uyarı")
        }
        val notification = Notification.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setColor(Palette.ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        notify(context, MORNING_ID, notification)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call; nothing to do.
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
