package com.mindfullness.weather.domain

import com.mindfullness.weather.data.ForecastParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AlertEngineTest {

    // Synthetic Istanbul forecast: rain this afternoon, a gale and fog tomorrow morning,
    // a cold front on day 3 and heavy showers on day 5.
    private val forecast: Forecast = ForecastParser.parse(
        javaClass.classLoader!!.getResource("forecast_istanbul.json")!!.readText(),
        fetchedAtMillis = 0,
    )
    private val now = LocalDateTime.of(2026, 10, 8, 14, 20)

    @Test
    fun `parses open-meteo response`() {
        assertEquals(240, forecast.hourly.size)
        assertEquals(10, forecast.daily.size)
        assertEquals(10800, forecast.utcOffsetSeconds)
        assertEquals(3, forecast.current.weatherCode)
        assertEquals(LocalDate.of(2026, 10, 8), forecast.daily.first().date)
    }

    @Test
    fun `detects afternoon rain with its time window`() {
        val rain = AlertEngine.evaluate(forecast, now).single { it.type == AlertType.RAIN && !it.outlook }
        assertEquals(Severity.YELLOW, rain.severity)
        assertEquals(LocalDateTime.of(2026, 10, 8, 15, 0), rain.start)
        assertEquals(LocalDateTime.of(2026, 10, 8, 21, 0), rain.end)
        assertEquals("Bugün 15:00–21:00", TimeText.range(rain.start, rain.end, now))
        assertTrue(rain.advice.isNotEmpty())
    }

    @Test
    fun `orders alerts by severity and keeps outlook last`() {
        val alerts = AlertEngine.evaluate(forecast, now)
        val first = alerts.first()
        assertEquals(AlertType.WIND, first.type)
        assertEquals(Severity.ORANGE, first.severity)
        assertTrue(alerts.any { it.type == AlertType.FOG && it.severity == Severity.YELLOW })
        assertTrue(alerts.any { it.type == AlertType.TEMPERATURE_DROP })
        val outlook = alerts.filter { it.outlook }
        assertEquals(listOf(AlertType.RAIN), outlook.map { it.type })
        assertEquals(LocalDate.of(2026, 10, 12), outlook.single().start.toLocalDate())
        assertTrue(alerts.takeLastWhile { it.outlook }.size == outlook.size)
    }

    @Test
    fun `no false alarms for mild conditions`() {
        val types = AlertEngine.evaluate(forecast, now).map { it.type }.toSet()
        assertFalse(AlertType.HEAT in types)
        assertFalse(AlertType.SNOW in types)
        assertFalse(AlertType.ICE in types)
        assertFalse(AlertType.UV in types)
    }

    @Test
    fun `precipitation summary names start and end`() {
        val summary = Insights.precipitation(forecast, now)
        assertTrue(summary.expectsPrecipitation)
        assertEquals("Yağış bekleniyor · 15:00 – 21:00", summary.headline)
    }

    @Test
    fun `tips suggest umbrella`() {
        val tips = Insights.tips(forecast, now).map { it.kind }
        assertTrue(TipKind.UMBRELLA in tips)
    }

    @Test
    fun `freezing rain becomes an ice warning`() {
        val hours = hours(LocalDateTime.of(2026, 1, 10, 0, 0), 48) { i ->
            if (i in 5..8) copy(weatherCode = 66, precipitation = 1.5, rain = 1.5, temperature = -1.0) else this
        }
        val ice = AlertEngine.evaluate(synthetic(hours), hours.first().time).single { it.type == AlertType.ICE }
        assertEquals(Severity.RED, ice.severity)
    }

    @Test
    fun `hail thunderstorm is at least orange`() {
        val hours = hours(LocalDateTime.of(2026, 6, 1, 0, 0), 48) { i ->
            if (i == 16) copy(weatherCode = 99, precipitation = 8.0, rain = 8.0) else this
        }
        val storm = AlertEngine.evaluate(synthetic(hours), hours.first().time).single { it.type == AlertType.THUNDERSTORM }
        assertTrue(storm.severity >= Severity.ORANGE)
    }

    @Test
    fun `heat and heavy snow are graded`() {
        val hot = hours(LocalDateTime.of(2026, 7, 20, 0, 0), 48) { i ->
            if (i in 12..16) copy(temperature = 38.0, apparentTemperature = 41.0) else this
        }
        assertEquals(
            Severity.ORANGE,
            AlertEngine.evaluate(synthetic(hot), hot.first().time).single { it.type == AlertType.HEAT }.severity,
        )

        val snowy = hours(LocalDateTime.of(2026, 1, 20, 0, 0), 48) { i ->
            if (i in 3..10) copy(weatherCode = 75, snowfall = 2.0, precipitation = 1.4, temperature = -2.0) else this
        }
        assertEquals(
            Severity.ORANGE,
            AlertEngine.evaluate(synthetic(snowy), snowy.first().time).single { it.type == AlertType.SNOW }.severity,
        )
    }

    @Test
    fun `wet roads followed by frost warn about black ice`() {
        val hours = hours(LocalDateTime.of(2026, 1, 5, 12, 0), 48) { i ->
            when (i) {
                in 4..6 -> copy(precipitation = 1.0, rain = 1.0, temperature = 3.0)
                in 9..14 -> copy(temperature = -1.0)
                else -> this
            }
        }
        val ice = AlertEngine.evaluate(synthetic(hours), hours.first().time).single { it.type == AlertType.ICE }
        assertEquals("Gizli buzlanma riski", ice.title)
    }

    @Test
    fun `episodes tolerate short gaps`() {
        val hours = hours(LocalDateTime.of(2026, 3, 1, 0, 0), 10) { this }
        val wet = setOf(1, 2, 4, 8)
        val episodes = AlertEngine.episodes(hours) { hours.indexOf(it) in wet }
        assertEquals(listOf(3, 1), episodes.map { it.size })
    }

    @Test
    fun `time ranges read naturally`() {
        val base = LocalDateTime.of(2026, 10, 8, 14, 20)
        assertEquals("Şimdi – 18:00", TimeText.range(base.withMinute(0), base.withHour(18).withMinute(0), base))
        assertEquals(
            "Bugün 22:00 – Yarın 04:00",
            TimeText.range(base.withHour(22).withMinute(0), base.plusDays(1).withHour(4).withMinute(0), base),
        )
        assertNotNull(TimeText.ago(0))
    }

    private fun hours(start: LocalDateTime, count: Int, modify: HourlyPoint.(Int) -> HourlyPoint): List<HourlyPoint> =
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

    private fun synthetic(hours: List<HourlyPoint>) = Forecast(
        fetchedAtMillis = 0,
        utcOffsetSeconds = 0,
        current = CurrentWeather(hours.first().time, 12.0, 11.0, 60, true, 0.0, 1, 20, 1013.0, 10.0, 180.0, 20.0),
        hourly = hours,
        daily = emptyList(),
    )
}
