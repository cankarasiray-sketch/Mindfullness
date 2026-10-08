package com.mindfullness.weather.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** Builders for synthetic forecasts: mild, dry weather unless a test changes it. */
internal fun hours(start: LocalDateTime, count: Int, modify: HourlyPoint.(Int) -> HourlyPoint): List<HourlyPoint> =
    (0 until count).map { i ->
        HourlyPoint(
            time = start.plusHours(i.toLong()),
            temperature = 12.0,
            apparentTemperature = 11.0,
            humidity = 60,
            precipitationProbability = 5,
            precipitation = 0.0,
            rain = 0.0,
            showers = 0.0,
            snowfall = 0.0,
            weatherCode = 1,
            windSpeed = 10.0,
            windGusts = 20.0,
            windDirection = 180.0,
            visibility = 20_000.0,
            uvIndex = 1.0,
            isDay = start.plusHours(i.toLong()).hour in 7..18,
        ).modify(i)
    }

internal fun day(date: LocalDate, apparentMax: Double) = DailyPoint(
    date = date, weatherCode = 1, temperatureMax = apparentMax - 2, temperatureMin = 18.0, apparentMax = apparentMax,
    apparentMin = 18.0, sunrise = null, sunset = null, uvIndexMax = 5.0, precipitationSum = 0.0,
    precipitationProbabilityMax = 0, precipitationHours = 0.0, snowfallSum = 0.0, windSpeedMax = 10.0,
    windGustsMax = 20.0, windDirectionDominant = 180.0,
)

internal fun synthetic(hours: List<HourlyPoint>) = Forecast(
    fetchedAtMillis = 0,
    utcOffsetSeconds = 0,
    current = CurrentWeather(hours.first().time, 12.0, 11.0, 60, true, 0.0, 1, 20, 1013.0, 10.0, 180.0, 20.0),
    hourly = hours,
    daily = emptyList(),
)
