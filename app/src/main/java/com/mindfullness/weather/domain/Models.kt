package com.mindfullness.weather.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

data class Place(
    val id: Long,
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val latitude: Double,
    val longitude: Double,
) {
    val isCurrentLocation: Boolean get() = id == CURRENT_LOCATION_ID

    val subtitle: String
        get() = listOfNotNull(region?.takeIf { it.isNotBlank() && it != name }, country?.takeIf { it.isNotBlank() })
            .joinToString(", ")

    companion object {
        const val CURRENT_LOCATION_ID = -1L
    }
}

data class Forecast(
    val fetchedAtMillis: Long,
    val utcOffsetSeconds: Int,
    val current: CurrentWeather,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
    /** The same hours from independent models (ECMWF, ICON, GFS), used to rate confidence. */
    val models: List<ModelRun> = emptyList(),
    /** Air quality and desert dust (CAMS), when available. */
    val air: List<AirPoint> = emptyList(),
) {
    /** Wall-clock time at the forecast location. */
    fun localNow(nowMillis: Long = System.currentTimeMillis()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.ofTotalSeconds(utcOffsetSeconds))

    fun today(now: LocalDateTime): DailyPoint? = daily.firstOrNull { it.date == now.toLocalDate() }

    /** Hourly points from the current hour onwards. */
    fun hoursFrom(now: LocalDateTime, count: Int): List<HourlyPoint> {
        val start = now.withMinute(0).withSecond(0).withNano(0)
        return hourly.asSequence().filter { !it.time.isBefore(start) }.take(count).toList()
    }

    fun hourAt(now: LocalDateTime): HourlyPoint? = hoursFrom(now, 1).firstOrNull()

    /** Air quality of the current hour. */
    fun airAt(now: LocalDateTime): AirPoint? {
        val start = now.withMinute(0).withSecond(0).withNano(0)
        return air.firstOrNull { !it.time.isBefore(start) }
    }

    /**
     * Points whose accumulated values (precipitation, rain, snowfall) fall from the current hour
     * onwards. Open-Meteo stamps an hour's sum at its end, so the 15:00 point covers 14:00–15:00.
     */
    fun precipitationHours(now: LocalDateTime, count: Int): List<HourlyPoint> {
        val start = now.withMinute(0).withSecond(0).withNano(0)
        return hourly.asSequence().filter { it.time.isAfter(start) }.take(count).toList()
    }
}

data class CurrentWeather(
    val time: LocalDateTime,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val isDay: Boolean,
    val precipitation: Double,
    val weatherCode: Int,
    val cloudCover: Int,
    val pressure: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val windGusts: Double,
)

data class HourlyPoint(
    val time: LocalDateTime,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val precipitationProbability: Int?,
    val precipitation: Double,
    val rain: Double,
    val showers: Double,
    val snowfall: Double,
    val weatherCode: Int,
    val windSpeed: Double,
    val windGusts: Double,
    val windDirection: Double,
    val visibility: Double?,
    val uvIndex: Double?,
    val isDay: Boolean,
    /** Convective available potential energy (J/kg): fuel for thunderstorms. */
    val cape: Double? = null,
) {
    val liquid: Double get() = rain + showers
}

/** Hourly series of one weather model; precipitation is the preceding-hour sum. */
data class ModelRun(val name: String, val hours: List<ModelHour>)

data class ModelHour(
    val time: LocalDateTime,
    val precipitation: Double?,
    val windGusts: Double?,
    val temperature: Double?,
)

data class AirPoint(
    val time: LocalDateTime,
    /** European Air Quality Index (0–20 good … 100+ extremely poor). */
    val europeanAqi: Double?,
    val pm25: Double?,
    val pm10: Double?,
    /** Desert dust near the surface, µg/m³. */
    val dust: Double?,
)

data class DailyPoint(
    val date: LocalDate,
    val weatherCode: Int,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val apparentMax: Double,
    val apparentMin: Double,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val uvIndexMax: Double?,
    val precipitationSum: Double,
    val precipitationProbabilityMax: Int?,
    val precipitationHours: Double,
    val snowfallSum: Double,
    val windSpeedMax: Double,
    val windGustsMax: Double,
    val windDirectionDominant: Double?,
)
