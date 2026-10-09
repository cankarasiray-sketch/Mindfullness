package com.mindfullness.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.LocalDateTime

/** Model agreement, convective risk, air quality, flood risk and the rain-soon check. */
class ForecastQualityTest {
    private val now = LocalDateTime.of(2026, 10, 8, 9, 0)
    private val base = now.toLocalDate().atStartOfDay()

    /** 3 h of rain, 4 mm each, in the points 16–18 (15:00–18:00). */
    private val rainyAfternoon = hours(base, 48) { i ->
        if (i in 16..18) copy(precipitation = 4.0, rain = 4.0, precipitationProbability = 80, weatherCode = 63) else this
    }

    @Test
    fun `models rate how certain a warning is`() {
        val later = model("ecmwf_ifs025") { i -> if (i in 17..19) 3.0 else 0.0 } // an hour late: still agrees
        val same = model("icon_seamless") { i -> if (i in 16..18) 5.0 else 0.0 }
        val dry = model("gfs_seamless") { 0.0 }
        val alert = AlertEngine.evaluate(synthetic(rainyAfternoon).copy(models = listOf(later, same, dry)), now)
            .single { it.type == AlertType.RAIN }

        assertEquals(Confidence(2, 3), alert.confidence)
        assertEquals(Confidence.Level.MEDIUM, alert.confidence!!.level)
        assertEquals("Güven: Orta · 2/3 model", alert.confidence!!.text)
        // Confidence is not part of the key, so a changed rating does not look like a new event.
        assertEquals(AlertEngine.evaluate(synthetic(rainyAfternoon), now).single { it.type == AlertType.RAIN }.key, alert.key)
    }

    @Test
    fun `models without data for the period are left out`() {
        val agrees = model("ecmwf_ifs025") { i -> if (i in 16..18) 4.0 else 0.0 }
        val alsoAgrees = model("icon_seamless") { i -> if (i in 15..18) 3.0 else 0.0 }
        val missing = model("gfs_seamless") { i -> if (i < 10) 0.0 else null }
        val alert = AlertEngine.evaluate(synthetic(rainyAfternoon).copy(models = listOf(agrees, alsoAgrees, missing)), now)
            .single { it.type == AlertType.RAIN }
        assertEquals(Confidence(2, 2), alert.confidence)
        assertEquals(Confidence.Level.HIGH, alert.confidence!!.level)

        // A single comparable model is not enough to rate anything.
        val single = AlertEngine.evaluate(synthetic(rainyAfternoon).copy(models = listOf(agrees, missing)), now)
            .single { it.type == AlertType.RAIN }
        assertNull(single.confidence)
    }

    @Test
    fun `unstable air warns about thunderstorms the weather code does not show`() {
        // Unstable 13:00–17:00; the shower chance of those hours sits on the points that close them (14–17).
        val unstable = hours(base, 48) { i ->
            copy(
                cape = if (i in 13..16) 1800.0 else cape,
                precipitationProbability = if (i in 14..17) 45 else precipitationProbability,
                weatherCode = if (i in 13..17) 3 else weatherCode,
                temperature = if (i in 13..17) 24.0 else temperature,
            )
        }
        val risk = AlertEngine.evaluate(synthetic(unstable), now).single { it.type == AlertType.THUNDERSTORM }
        assertEquals("Gök gürültülü sağanak riski", risk.title)
        assertEquals(Severity.YELLOW, risk.severity)
        assertEquals(base.plusHours(13), risk.start)
        assertEquals(base.plusHours(17), risk.end)
        assertTrue(risk.detail, risk.detail.contains("1800 J/kg"))

        // Once the forecast itself shows the storm, only that warning remains.
        val storm = unstable.map { if (it.time.hour in 14..15 && it.time.toLocalDate() == base.toLocalDate()) it.copy(weatherCode = 95) else it }
        val alerts = AlertEngine.evaluate(synthetic(storm), now).filter { it.type == AlertType.THUNDERSTORM }
        assertEquals(listOf("Gök gürültülü sağanak"), alerts.map { it.title })

        // Cold-season instability or a low chance of showers is not worth a warning.
        val cold = unstable.map { it.copy(temperature = 4.0) }
        assertTrue(AlertEngine.evaluate(synthetic(cold), now).none { it.type == AlertType.THUNDERSTORM })
    }

    @Test
    fun `a storm that is over or weaker does not hide the instability warning`() {
        // Very unstable 10:00–21:00 with a high shower chance; it is 14:00.
        val unstable = hours(base, 48) { i ->
            copy(
                cape = if (i in 10..20) 3000.0 else cape,
                precipitationProbability = if (i in 11..21) 70 else precipitationProbability,
                temperature = 25.0,
            )
        }
        val afternoon = base.plusHours(14)
        // The thunderstorm the forecast showed at 11:00 has passed: the risk for the rest of the day stays.
        val past = unstable.map { if (it.time == base.plusHours(11)) it.copy(weatherCode = 95) else it }
        val risk = AlertEngine.evaluate(synthetic(past), afternoon).single { it.type == AlertType.THUNDERSTORM }
        assertEquals("Gök gürültülü sağanak riski", risk.title)
        assertEquals(Severity.ORANGE, risk.severity)
        assertEquals(base.plusHours(21), risk.end)

        // A single yellow storm hour in the evening does not replace the orange risk.
        val evening = unstable.map { if (it.time == base.plusHours(19)) it.copy(weatherCode = 95) else it }
        val alerts = AlertEngine.evaluate(synthetic(evening), afternoon).filter { it.type == AlertType.THUNDERSTORM }
        assertTrue(alerts.any { it.title == "Gök gürültülü sağanak riski" && it.severity == Severity.ORANGE })
    }

    @Test
    fun `desert dust and poor air are told apart`() {
        fun air(aqi: Double, pm10: Double, dust: Double) = (0 until 48).map { i ->
            val event = i in 10..14
            AirPoint(base.plusHours(i.toLong()), if (event) aqi else 25.0, 15.0, if (event) pm10 else 20.0, if (event) dust else 3.0)
        }
        val dust = AlertEngine.evaluate(synthetic(hours(base, 48) { this }).copy(air = air(85.0, 160.0, 120.0)), now)
            .single { it.type == AlertType.DUST }
        assertEquals("Yoğun çöl tozu", dust.title)
        assertEquals(Severity.YELLOW, dust.severity)
        assertEquals(base.plusHours(10), dust.start)
        assertEquals(base.plusHours(15), dust.end)
        assertTrue(dust.advice.any { it.contains("çamur") })

        val smog = AlertEngine.evaluate(synthetic(hours(base, 48) { this }).copy(air = air(65.0, 70.0, 5.0)), now)
            .single { it.type == AlertType.AIR_QUALITY }
        assertEquals("Hava kalitesi kötü", smog.title)
        assertEquals(Severity.INFO, smog.severity)

        // The mask tip names the same cause as the warning.
        val smogDay = synthetic(hours(base, 48) { this }).copy(air = air(85.0, 60.0, 30.0))
        assertEquals("Hava kalitesi çok kötü", AlertEngine.evaluate(smogDay, now).single { it.type == AlertType.AIR_QUALITY }.title)
        assertEquals("Maske (hava kirliliği)", Insights.tips(smogDay, now).single { it.kind == TipKind.MASK }.text)
        val dustDay = synthetic(hours(base, 48) { this }).copy(air = air(85.0, 160.0, 120.0))
        assertEquals("Maske (çöl tozu)", Insights.tips(dustDay, now).single { it.kind == TipKind.MASK }.text)

        val clean = AlertEngine.evaluate(synthetic(hours(base, 48) { this }).copy(air = air(35.0, 30.0, 10.0)), now)
        assertTrue(clean.none { it.type == AlertType.AIR_QUALITY || it.type == AlertType.DUST })
    }

    @Test
    fun `rain adding up over days warns about flooding`() {
        val today = now.toLocalDate()
        val daily = listOf(30.0, 25.0, 20.0, 0.0).mapIndexed { i, mm -> day(today.plusDays(i - 1L), 20.0).copy(precipitationSum = mm) }
        val forecast = synthetic(hours(base, 48) { this }).copy(daily = daily)
        val flood = AlertEngine.evaluate(forecast, now).single { it.type == AlertType.FLOOD }
        assertEquals(Severity.YELLOW, flood.severity)
        // The 3-day window began yesterday; the start (and key) stays put as the days pass.
        assertEquals(today.minusDays(1).atStartOfDay(), flood.start)
        assertEquals(today.plusDays(2).atStartOfDay(), flood.end)
        assertFalse(flood.outlook)
        assertEquals("Bugün – Yarın", flood.whenText(now))
        assertTrue(flood.detail, flood.detail.contains("75 mm"))
        val tomorrow = AlertEngine.evaluate(forecast, now.plusDays(1)).single { it.type == AlertType.FLOOD }
        assertEquals(flood.key, tomorrow.key)
        assertEquals("Bugün, 9 Ekim", tomorrow.whenText(now.plusDays(1)))

        // A heavy-rain warning for the same period already says it.
        val downpour = hours(base, 48) { i ->
            if (i in 16..19) copy(precipitation = 11.0, rain = 11.0, precipitationProbability = 90, weatherCode = 65) else this
        }
        val alerts = AlertEngine.evaluate(synthetic(downpour).copy(daily = daily), now)
        assertTrue(alerts.any { it.type == AlertType.RAIN && it.severity == Severity.ORANGE })
        assertTrue(alerts.none { it.type == AlertType.FLOOD })

        val dry = daily.map { it.copy(precipitationSum = 15.0) }
        assertTrue(AlertEngine.evaluate(synthetic(hours(base, 48) { this }).copy(daily = dry), now).none { it.type == AlertType.FLOOD })
    }

    @Test
    fun `rain soon is announced once, before it starts`() {
        val shower = hours(base, 48) { i ->
            if (i in 16..17) copy(precipitation = 1.2, rain = 1.2, precipitationProbability = 70, weatherCode = 61) else this
        }
        val forecast = synthetic(shower)

        assertNull(AlertEngine.upcomingRain(forecast, base.plusHours(12).plusMinutes(10)))
        val early = AlertEngine.upcomingRain(forecast, base.plusHours(14).plusMinutes(10))
        assertNotNull(early)
        assertEquals(base.plusHours(15), early!!.start)
        assertEquals(70, early.probability)
        assertEquals(2.4, early.amount, 0.001)
        assertFalse(early.snow)

        // Later checks see the same event, so the notification is not repeated.
        val late = AlertEngine.upcomingRain(forecast, base.plusHours(15).plusMinutes(20))!!
        assertEquals(early.key, late.key)
        assertEquals(base.plusHours(15).plusMinutes(20), late.start)

        // Nothing to announce while it is already raining.
        val raining = forecast.copy(current = forecast.current.copy(precipitation = 0.6, weatherCode = 61))
        assertNull(AlertEngine.upcomingRain(raining, base.plusHours(14).plusMinutes(10)))
    }

    @Test
    fun `shared text carries the essentials`() {
        val alert = AlertEngine.evaluate(synthetic(rainyAfternoon), now).single { it.type == AlertType.RAIN }
        val text = alert.shareText("Kadıköy", now)
        assertTrue(text, text.startsWith("Bilgi · ${alert.title}\nKadıköy · Bugün 15:00–18:00"))
        assertTrue(text, text.contains("• Şemsiye"))
    }

    private fun model(name: String, precipitation: (Int) -> Double?) = ModelRun(
        name,
        (0 until 48).map { i ->
            val value = precipitation(i)
            ModelHour(base.plusHours(i.toLong()), value, value?.let { 20.0 }, value?.let { 12.0 })
        },
    )
}
