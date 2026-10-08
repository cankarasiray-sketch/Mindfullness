package com.mindfullness.weather.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import kotlin.coroutines.cancellation.CancellationException

object AppGraph {
    @Volatile private var repository: WeatherRepository? = null

    fun repository(context: Context): WeatherRepository = repository ?: synchronized(this) {
        repository ?: WeatherRepository(OpenMeteoApi(), File(context.applicationContext.cacheDir, "forecasts"))
            .also { repository = it }
    }
}

/** Periodically checks the forecast for the notification place and posts new warnings. */
class AlertCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val settings = store.settings.value
        val place = settings.notificationPlace
        if (!settings.alertsEnabled || place == null || !Notifier.canNotify(applicationContext)) return Result.success()

        val forecast = fetchOrNull(applicationContext, place) ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
        val now = forecast.localNow()
        AlertEngine.evaluate(forecast, now).asSequence()
            .filter { !it.outlook && it.severity >= settings.minSeverity && it.start.isBefore(now.plusHours(24)) }
            .filter { store.markNotified(it.key) }
            .take(3)
            .forEach { Notifier.showAlert(applicationContext, place, it, now) }
        return Result.success()
    }
}

/** Posts the 07:00 summary and schedules the next one. */
class MorningSummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val settings = store.settings.value
        if (!settings.morningSummary) return Result.success()
        val place = settings.notificationPlace ?: store.lastCurrentPlace ?: store.places.value.firstOrNull()

        if (place != null) {
            val forecast = fetchOrNull(applicationContext, place)
            if (forecast == null && runAttemptCount < 2) return Result.retry()
            if (forecast != null) {
                val now = forecast.localNow()
                val important = AlertEngine.evaluate(forecast, now)
                    .count { !it.outlook && it.severity >= Severity.YELLOW && it.start.isBefore(now.plusHours(24)) }
                Notifier.showMorningSummary(applicationContext, place, Insights.morningSummary(forecast, now), important)
            }
        }
        WorkScheduler.scheduleMorning(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

private suspend fun fetchOrNull(context: Context, place: Place): Forecast? = try {
    AppGraph.repository(context).fetch(place)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

object WorkScheduler {
    private const val ALERTS = "alert-check"
    private const val ALERTS_NOW = "alert-check-now"
    private const val MORNING = "morning-summary"
    private val MORNING_TIME: LocalTime = LocalTime.of(7, 0)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun apply(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        if (settings.alertsEnabled) {
            val request = PeriodicWorkRequestBuilder<AlertCheckWorker>(3, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            manager.enqueueUniquePeriodicWork(ALERTS, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            manager.cancelUniqueWork(ALERTS)
        }
        if (settings.morningSummary) scheduleMorning(context, ExistingWorkPolicy.KEEP) else manager.cancelUniqueWork(MORNING)
    }

    /** Runs an alert check right away, e.g. after notifications were switched on. */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AlertCheckWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ALERTS_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleMorning(context: Context, policy: ExistingWorkPolicy) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(MORNING_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = OneTimeWorkRequestBuilder<MorningSummaryWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MORNING, policy, request)
    }
}
