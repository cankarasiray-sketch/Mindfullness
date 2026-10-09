package com.mindfullness.weather.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.mindfullness.weather.R
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.Condition
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Insights
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherCodes
import com.mindfullness.weather.ui.Palette
import com.mindfullness.weather.ui.WeatherArt
import com.mindfullness.weather.ui.deg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: current conditions and the most important alert for the widget place
 * (the notification place, else the device location or the first saved place). Fresh data comes
 * from the background check, which runs hourly while a widget exists, and from the app itself.
 */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        Async.background {
            try {
                refresh(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        val pending = goAsync()
        Async.background {
            try {
                updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        JobScheduling.apply(context, SettingsStore(context).settings)
    }

    override fun onDisabled(context: Context) {
        JobScheduling.apply(context, SettingsStore(context).settings)
    }

    companion object {
        private const val STALE_MILLIS = 45 * 60_000L

        fun isInUse(context: Context): Boolean = ids(context).isNotEmpty()

        private fun ids(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))
        }.getOrNull() ?: IntArray(0)

        /** Redraws from the cache and asks for a background fetch when the data is old. Blocking. */
        fun refresh(context: Context) {
            if (!isInUse(context)) return
            val forecast = updateAll(context)
            if (forecast == null || System.currentTimeMillis() - forecast.fetchedAtMillis > STALE_MILLIS) {
                JobScheduling.checkNow(context)
            }
        }

        /** The app fetched [forecast] for [place]: update the widgets if they show that place. */
        fun onForecast(context: Context, place: Place, forecast: Forecast) {
            val app = context.applicationContext
            Async.background {
                if (isInUse(app) && SettingsStore(app).widgetPlace?.id == place.id) updateAll(app, place, forecast)
            }
        }

        /** Same as [refresh], off the main thread: for settings changes that move the widget place. */
        fun requestRefresh(context: Context) {
            val app = context.applicationContext
            Async.background { refresh(app) }
        }

        /**
         * Draws every widget. Uses [forecast] when it belongs to the widget place, else the cache.
         * Returns the forecast shown. Blocking: call off the main thread.
         */
        fun updateAll(context: Context, place: Place? = null, forecast: Forecast? = null): Forecast? {
            val ids = ids(context)
            if (ids.isEmpty()) return null
            val store = SettingsStore(context)
            val target = store.widgetPlace
            val shown = when {
                target == null -> null
                forecast != null && place?.id == target.id -> forecast
                else -> AppGraph.repository(context).cached(target)
            }
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                val compact = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) < 100
                val views = render(context, target, shown, compact)
                runCatching { manager.updateAppWidget(id, views) }
            }
            return shown
        }

        internal fun render(context: Context, place: Place?, forecast: Forecast?, compact: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setViewVisibility(R.id.widget_alert, if (compact) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_updated, if (compact) View.GONE else View.VISIBLE)
            if (place != null) views.setOnClickPendingIntent(R.id.widget_root, Notifier.openApp(context, place))
            else views.setOnClickPendingIntent(R.id.widget_root, Notifier.openApp(context))

            val now = forecast?.localNow()
            if (place == null || forecast == null || now == null || forecast.hoursFrom(now, 1).isEmpty()) {
                views.setTextViewText(R.id.widget_place, place?.name ?: context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_temp, "–")
                views.setTextViewText(R.id.widget_condition, if (place == null) "Konum seçilmedi" else "Güncel veri yok")
                views.setTextViewText(R.id.widget_range, "")
                val inset = (10 * context.resources.displayMetrics.density).toInt()
                views.setViewPadding(R.id.widget_icon, inset, inset, inset, inset)
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_refresh)
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_night)
                dot(views, Palette.ACCENT, "Güncellemek için uygulamayı açın")
                views.setTextViewText(R.id.widget_updated, "")
                return views
            }

            val current = forecast.current
            val today = forecast.today(now)
            views.setTextViewText(R.id.widget_place, place.name)
            views.setTextViewText(R.id.widget_temp, current.temperature.deg())
            views.setTextViewText(R.id.widget_condition, WeatherCodes.describe(current.weatherCode))
            views.setTextViewText(R.id.widget_range, today?.let { "↑${it.temperatureMax.deg()}  ↓${it.temperatureMin.deg()}" }.orEmpty())
            views.setViewPadding(R.id.widget_icon, 0, 0, 0, 0)
            views.setImageViewBitmap(R.id.widget_icon, icon(context, current.weatherCode, current.isDay))
            views.setInt(R.id.widget_root, "setBackgroundResource", background(current.weatherCode, current.isDay))
            views.setContentDescription(
                R.id.widget_root,
                "${place.name}, ${current.temperature.deg()}, ${WeatherCodes.describe(current.weatherCode)}",
            )

            val top = AlertEngine.evaluate(forecast, now).firstOrNull { !it.outlook && it.severity >= Severity.YELLOW }
            val rain = Insights.precipitation(forecast, now)
            when {
                top != null -> dot(views, Palette.severity(top.severity), "${top.title} · ${top.whenText(now)}")
                rain.expectsPrecipitation -> dot(views, Palette.RAIN, rain.headline)
                else -> dot(views, Palette.GOOD, "Önemli bir hava olayı beklenmiyor")
            }
            // Device time zone; the app avoids region zones (no time-zone database is bundled).
            val fetched = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(forecast.fetchedAtMillis))
            views.setTextViewText(R.id.widget_updated, "$fetched itibarıyla")
            return views
        }

        private fun dot(views: RemoteViews, color: Int, text: String) {
            views.setInt(R.id.widget_dot, "setColorFilter", color)
            views.setTextViewText(R.id.widget_alert_text, text)
        }

        private fun icon(context: Context, code: Int, isDay: Boolean): Bitmap {
            val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            WeatherArt.draw(Canvas(bitmap), code, isDay, size.toFloat())
            return bitmap
        }

        private fun background(code: Int, isDay: Boolean): Int = when (WeatherCodes.condition(code)) {
            Condition.THUNDERSTORM, Condition.HAIL -> R.drawable.widget_bg_storm
            Condition.DRIZZLE, Condition.RAIN, Condition.SHOWERS, Condition.HEAVY_RAIN, Condition.FREEZING_RAIN ->
                R.drawable.widget_bg_rain
            Condition.OVERCAST, Condition.FOG, Condition.SNOW, Condition.SLEET -> R.drawable.widget_bg_cloudy
            else -> if (isDay) R.drawable.widget_bg_day else R.drawable.widget_bg_night
        }
    }
}
