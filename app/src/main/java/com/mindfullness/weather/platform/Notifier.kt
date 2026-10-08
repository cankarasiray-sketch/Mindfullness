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
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.RainSoon
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.TimeText
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.ui.Palette
import java.time.Duration
import java.time.LocalDateTime

object Notifier {
    /** Extra carrying the id of the place a notification is about. */
    const val EXTRA_PLACE_ID = "com.mindfullness.weather.PLACE_ID"
    private const val CHANNEL_ALERTS = "weather_alerts"
    private const val CHANNEL_DAILY = "daily_summary"
    private const val CHANNEL_RAIN = "rain_soon"
    private const val MORNING_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Hava uyarıları", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Yağış, fırtına, don, sis ve aşırı sıcak gibi önemli hava olayları"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RAIN, "Yağmur yaklaşıyor", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Bulunduğunuz yerde yağmur veya kar başlamadan önce kısa bir haber"
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
            .setContentIntent(openApp(context, place))
            .setAutoCancel(true)
            .build()
        // Same event → same id, so an upgraded warning replaces the earlier notification.
        notify(context, (place.id.toString() + alert.key).hashCode(), notification)
    }

    fun showRainSoon(context: Context, place: Place, rain: RainSoon, now: LocalDateTime) {
        if (!canNotify(context)) return
        val minutes = Duration.between(now, rain.start).toMinutes()
        val text = buildString {
            append(if (minutes <= 15) "Birazdan" else "${TimeText.hour(rain.start)} civarında")
            append(" başlaması bekleniyor")
            if (!rain.snow && rain.amount >= 0.1) append(" · ~${AlertEngine.formatAmount(rain.amount)} mm")
            rain.probability?.let { append(" · olasılık %$it") }
        }
        val notification = Notification.Builder(context, CHANNEL_RAIN)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setColor(Palette.RAIN)
            .setContentTitle(if (rain.snow) "❄️ Kar yaklaşıyor · ${place.name}" else "☔ Yağmur yaklaşıyor · ${place.name}")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(openApp(context, place))
            // Pointless once the shower is over.
            .setTimeoutAfter(Duration.between(now, rain.start.plusHours(2)).toMillis().coerceAtLeast(60_000L))
            .setAutoCancel(true)
            .build()
        notify(context, ("rain_soon" + place.id).hashCode(), notification)
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
            .setContentIntent(openApp(context, place))
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

    /** Opens the app as it was, for a widget that has no place yet. */
    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        // Distinct from every place's request code, so it never replaces their extras.
        Int.MIN_VALUE,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens the app on [place]; shared by notifications and the home-screen widget. */
    fun openApp(context: Context, place: Place): PendingIntent = PendingIntent.getActivity(
        context,
        place.id.hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PLACE_ID, place.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
