package com.mindfullness.weather.ui.home

import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Insights
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.PrecipitationSummary
import com.mindfullness.weather.domain.Tip
import com.mindfullness.weather.domain.WeatherAlert
import java.time.LocalDateTime

data class HomeUiState(
    val place: Place? = null,
    val content: ForecastContent? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLocating: Boolean = false,
    /** Set when the last refresh failed; the screen then shows cached data if it has any. */
    val errorMessage: String? = null,
    val needsOnboarding: Boolean = false,
)

/** Everything derived from one forecast, computed once outside of composition. */
data class ForecastContent(
    val forecast: Forecast,
    val now: LocalDateTime,
    val alerts: List<WeatherAlert>,
    val tips: List<Tip>,
    val precipitation: PrecipitationSummary,
) {
    val isStale: Boolean get() = System.currentTimeMillis() - forecast.fetchedAtMillis > STALE_AFTER_MILLIS

    companion object {
        const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L

        fun from(forecast: Forecast, nowMillis: Long = System.currentTimeMillis()): ForecastContent {
            val now = forecast.localNow(nowMillis)
            return ForecastContent(
                forecast = forecast,
                now = now,
                alerts = AlertEngine.evaluate(forecast, now),
                tips = Insights.tips(forecast, now),
                precipitation = Insights.precipitation(forecast, now),
            )
        }
    }
}
