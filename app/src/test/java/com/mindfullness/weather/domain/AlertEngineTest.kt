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
    fun `black ice is still reported after the rain has stopped`() {
        // Rain 12:00–14:00 (points 13 and 14 hold the preceding hour), frost from 17:00.
        val hours = hours(LocalDateTime.of(2026, 1, 5, 0, 0), 48) { i ->
            when (i) {
                13, 14 -> copy(precipitation = 2.0, rain = 2.0, temperature = 3.0)
                in 17..21 -> copy(temperature = -2.0)
                else -> this
            }
        }
        val later = LocalDateTime.of(2026, 1, 5, 16, 10)
        val ice = AlertEngine.evaluate(synthetic(hours), later).single { it.type == AlertType.ICE }
        assertEquals("Gizli buzlanma riski", ice.title)
        assertEquals(LocalDateTime.of(2026, 1, 5, 17, 0), ice.start)
    }

    @Test
    fun `freezing rain elsewhere does not hide tonight's black ice`() {
        val hours = hours(LocalDateTime.of(2026, 1, 5, 0, 0), 72) { i ->
            when (i) {
                13, 14 -> copy(precipitation = 2.0, rain = 2.0, temperature = 3.0)
                in 17..21 -> copy(temperature = -2.0)
                42 -> copy(weatherCode = 66, precipitation = 1.0, rain = 1.0, temperature = -1.0)
                else -> this
            }
        }
        val alerts = AlertEngine.evaluate(synthetic(hours), LocalDateTime.of(2026, 1, 5, 15, 0))
        assertTrue(alerts.any { it.title == "Gizli buzlanma riski" })
        assertTrue(alerts.any { it.title.startsWith("Dondurucu yağmur") })
    }

    @Test
    fun `an ongoing event keeps its key while time passes`() {
        // Rain 22:00–04:00.
        val hours = hours(LocalDateTime.of(2026, 1, 10, 0, 0), 72) { i ->
            if (i in 23..28) copy(precipitation = 6.0, rain = 6.0, precipitationProbability = 90) else this
        }
        val forecast = synthetic(hours)
        val evening = AlertEngine.evaluate(forecast, LocalDateTime.of(2026, 1, 10, 22, 10)).single { it.type == AlertType.RAIN }
        val night = AlertEngine.evaluate(forecast, LocalDateTime.of(2026, 1, 11, 1, 10)).single { it.type == AlertType.RAIN }
        assertEquals(evening.key, night.key)
        assertEquals(evening.severity, night.severity)
        assertEquals(LocalDateTime.of(2026, 1, 10, 22, 0), night.start)
        assertEquals("Şimdi – 04:00", TimeText.range(night.start, night.end, LocalDateTime.of(2026, 1, 11, 1, 10)))
    }

    @Test
    fun `mixed rain and snow still grades the rain`() {
        val hours = hours(LocalDateTime.of(2026, 1, 20, 0, 0), 48) { i ->
            if (i in 3..8) copy(precipitation = 9.0, rain = 8.8, snowfall = 0.15, temperature = 1.0, precipitationProbability = 90) else this
        }
        val rain = AlertEngine.evaluate(synthetic(hours), hours.first().time).single { it.type == AlertType.RAIN }
        assertEquals(Severity.ORANGE, rain.severity)
    }

    @Test
    fun `a weak partial-day alert does not hide a stronger outlook`() {
        val forecast = synthetic(
            hours(LocalDateTime.of(2026, 7, 20, 0, 0), 72) { i ->
                if (i in 59..60) copy(apparentTemperature = 33.0, temperature = 31.0) else this
            },
        ).copy(daily = (0..4).map { day(LocalDate.of(2026, 7, 20).plusDays(it.toLong()), apparentMax = if (it == 2) 41.0 else 28.0) })
        val alerts = AlertEngine.evaluate(forecast, LocalDateTime.of(2026, 7, 20, 13, 5))
        assertTrue(alerts.any { it.outlook && it.type == AlertType.HEAT && it.severity == Severity.ORANGE })
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
}
