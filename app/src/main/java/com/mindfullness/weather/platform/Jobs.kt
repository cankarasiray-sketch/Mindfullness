package com.mindfullness.weather.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.mindfullness.weather.data.OpenMeteoApi
import com.mindfullness.weather.data.WeatherRepository
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Insights
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object AppGraph {
    @Volatile private var repository: WeatherRepository? = null

    fun repository(context: Context): WeatherRepository = repository ?: synchronized(this) {
        repository ?: WeatherRepository(OpenMeteoApi(), File(context.applicationContext.cacheDir, "forecasts"))
            .also { repository = it }
    }
}

/** Checks the forecast for the notification place and posts warnings not announced yet. */
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
            val place = settings.notificationPlace
            if (!settings.alertsEnabled || place == null || !Notifier.canNotify(context)) return true
            val forecast = fetchOrNull(context, place) ?: return false
            val now = forecast.localNow()
            AlertEngine.evaluate(forecast, now).asSequence()
                .filter { !it.outlook && it.severity >= settings.minSeverity && it.start.isBefore(now.plusHours(24)) }
                .filter { store.markNotified(it.key) }
                .take(3)
                .forEach { Notifier.showAlert(context, place, it, now) }
            return true
        }
    }
}

/** Posts the 07:00 summary and schedules the next one. */
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
    private val MORNING_TIME: LocalTime = LocalTime.of(7, 0)

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    fun apply(context: Context, settings: AppSettings) {
        val scheduler = scheduler(context) ?: return
        if (settings.alertsEnabled) {
            if (scheduler.getPendingJob(ALERT_JOB) == null) {
                val job = JobInfo.Builder(ALERT_JOB, ComponentName(context, AlertJobService::class.java))
                    .setPeriodic(TimeUnit.HOURS.toMillis(3), TimeUnit.HOURS.toMillis(1))
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
            if (scheduler.getPendingJob(MORNING_JOB) == null) scheduleMorning(context)
        } else {
            scheduler.cancel(MORNING_JOB)
        }
    }

    /** Runs an alert check soon, e.g. right after notifications were switched on. */
    fun checkNow(context: Context) {
        val job = JobInfo.Builder(ALERT_NOW_JOB, ComponentName(context, AlertJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(TimeUnit.MINUTES.toMillis(10))
            .build()
        scheduler(context)?.schedule(job)
    }

    fun scheduleMorning(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(MORNING_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val job = JobInfo.Builder(MORNING_JOB, ComponentName(context, MorningJobService::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + TimeUnit.MINUTES.toMillis(45))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        scheduler(context)?.schedule(job)
    }
}
