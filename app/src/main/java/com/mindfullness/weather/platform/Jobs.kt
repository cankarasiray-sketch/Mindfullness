package com.mindfullness.weather.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.mindfullness.weather.data.OpenMeteoApi
import com.mindfullness.weather.data.WeatherRepository
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.AlertType
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Insights
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import java.io.File
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object AppGraph {
    @Volatile private var repository: WeatherRepository? = null

    fun repository(context: Context): WeatherRepository = repository ?: synchronized(this) {
        repository ?: WeatherRepository(OpenMeteoApi(), File(context.applicationContext.cacheDir, "forecasts"))
            .also { repository = it }
    }
}

/**
 * Checks the forecast for the notification place, posts warnings not announced yet and the
 * "rain soon" heads-up, and refreshes the home-screen widgets.
 */
class AlertJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Async.background {
            val retry = !runCheck(applicationContext)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        /** Returns false when the forecast could not be fetched and the job should be retried. */
        fun runCheck(context: Context): Boolean {
            val store = SettingsStore(context)
            val settings = store.settings
            val alertPlace = settings.notificationPlace?.takeIf { settings.alertsEnabled && Notifier.canNotify(context) }
            val widgets = WeatherWidget.isInUse(context)
            // The widget shows the notification place whenever there is one, so one fetch serves both.
            val place = alertPlace ?: store.widgetPlace?.takeIf { widgets } ?: return true
            val forecast = fetchOrNull(context, place)
            if (widgets) WeatherWidget.updateAll(context, place, forecast)
            if (forecast == null) return false
            if (alertPlace == null) return true

            val now = forecast.localNow()
            // Lazy, so only the alerts actually posted are recorded as notified.
            val posted = AlertEngine.evaluate(forecast, now).asSequence()
                .filter { !it.outlook && it.severity >= settings.minSeverity && it.start.isBefore(now.plusHours(24)) }
                .filter { store.markNotified(it.key, it.severity.ordinal) }
                .take(3)
                .toList()
            posted.forEach { Notifier.showAlert(context, place, it, now) }
            if (settings.rainSoon) {
                val rain = AlertEngine.upcomingRain(forecast, now)
                // A precipitation warning posted just now already tells about the same shower.
                val covered = rain != null && posted.any {
                    it.type in PRECIPITATION && it.start.isBefore(rain.hour.plusHours(2)) && it.end.isAfter(rain.hour)
                }
                if (rain != null && store.markNotified(rain.key, 0) && !covered) Notifier.showRainSoon(context, place, rain, now)
            }
            return true
        }

        private val PRECIPITATION = setOf(AlertType.RAIN, AlertType.SNOW, AlertType.THUNDERSTORM, AlertType.ICE)
    }
}

/** Posts the 06:00 summary and schedules the next one. */
class MorningJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Async.background {
            val context = applicationContext
            val store = SettingsStore(context)
            val settings = store.settings
            val place = settings.notificationPlace ?: store.lastCurrentPlace ?: store.places.firstOrNull()
            if (settings.morningSummary && place != null) {
                val forecast = fetchOrNull(context, place)
                if (forecast != null) {
                    val now = forecast.localNow()
                    val important = AlertEngine.evaluate(forecast, now)
                        .count { !it.outlook && it.severity >= Severity.YELLOW && it.start.isBefore(now.plusHours(24)) }
                    Notifier.showMorningSummary(context, place, Insights.morningSummary(forecast, now), important)
                }
            }
            jobFinished(params, false)
            // Scheduling the same job id while it runs would stop it, so the next one is queued afterwards.
            if (store.settings.morningSummary) JobScheduling.scheduleMorning(context)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

private fun fetchOrNull(context: Context, place: Place): Forecast? =
    try {
        AppGraph.repository(context).fetch(place)
    } catch (e: Exception) {
        null
    }

object JobScheduling {
    private const val ALERT_JOB = 1001
    private const val ALERT_NOW_JOB = 1002
    private const val MORNING_JOB = 1003
    private val MORNING_TIME: LocalTime = LocalTime.of(6, 0)
    /** Extra recording the time a morning job was scheduled for, so a changed time replaces it. */
    private const val EXTRA_MORNING_MINUTE = "morning_minute"

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    fun apply(context: Context, settings: AppSettings) {
        val scheduler = scheduler(context) ?: return
        val widgets = WeatherWidget.isInUse(context)
        if (settings.alertsEnabled || widgets) {
            // "Rain soon" needs hourly checks to give an hour's notice; widgets should not lag either.
            val hourly = (settings.alertsEnabled && settings.rainSoon) || widgets
            val period = TimeUnit.HOURS.toMillis(if (hourly) 1 else 3)
            if (scheduler.getPendingJob(ALERT_JOB)?.intervalMillis != period) {
                val job = JobInfo.Builder(ALERT_JOB, ComponentName(context, AlertJobService::class.java))
                    .setPeriodic(period, if (hourly) TimeUnit.MINUTES.toMillis(20) else TimeUnit.HOURS.toMillis(1))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            }
        } else {
            scheduler.cancel(ALERT_JOB)
            scheduler.cancel(ALERT_NOW_JOB)
        }
        if (settings.morningSummary) {
            val pending = scheduler.getPendingJob(MORNING_JOB)
            // Jobs from older versions (07:00, without the extra) are moved to the current time.
            if (pending == null || pending.extras.getInt(EXTRA_MORNING_MINUTE, -1) != morningMinute) scheduleMorning(context)
        } else {
            scheduler.cancel(MORNING_JOB)
        }
    }

    /** Runs an alert check soon, e.g. right after notifications were switched on or a widget was added. */
    fun checkNow(context: Context) {
        val job = JobInfo.Builder(ALERT_NOW_JOB, ComponentName(context, AlertJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(TimeUnit.MINUTES.toMillis(10))
            .build()
        scheduler(context)?.schedule(job)
    }

    fun scheduleMorning(context: Context) {
        // Zoned times keep the summary at 06:00 local across daylight-saving changes.
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(MORNING_TIME).atZone(zone)
        // A run that fires slightly early must not schedule a second summary for the same morning.
        if (!next.isAfter(now.plusMinutes(30))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val job = JobInfo.Builder(MORNING_JOB, ComponentName(context, MorningJobService::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + TimeUnit.MINUTES.toMillis(45))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putInt(EXTRA_MORNING_MINUTE, morningMinute) })
            .build()
        scheduler(context)?.schedule(job)
    }

    private val morningMinute: Int get() = MORNING_TIME.hour * 60 + MORNING_TIME.minute
}
