package com.mindfullness.weather.platform

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.ui.graphics.toArgb
import com.mindfullness.weather.MainActivity
import com.mindfullness.weather.R
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.ui.theme.AppColors
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

    fun canNotify(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setColor(AppColors.severity(alert.severity).toArgb())
            .setContentTitle("$marker ${alert.title} · ${place.name}")
            .setContentText("${alert.whenText(now)} · ${alert.detail}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (alert.severity >= Severity.ORANGE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(alert.key.hashCode(), notification)
    }

    @SuppressLint("MissingPermission")
    fun showMorningSummary(context: Context, place: Place, text: String, importantAlerts: Int) {
        if (!canNotify(context)) return
        val title = buildString {
            append("Günaydın · ").append(place.name)
            if (importantAlerts > 0) append(" · $importantAlerts uyarı")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setColor(AppColors.Primary.toArgb())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(MORNING_ID, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
