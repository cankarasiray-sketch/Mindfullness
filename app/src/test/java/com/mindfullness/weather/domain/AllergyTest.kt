package com.mindfullness.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.LocalDateTime

class AllergyTest {
    private val now = LocalDateTime.of(2026, 5, 12, 9, 0)
    private val base = now.toLocalDate().atStartOfDay()

    /** Three days of hourly pollen; [grains] gives the value per type and hour index (null: no data). */
    private fun air(grains: (PollenType, Int) -> Double?) = (0 until 72).map { i ->
        val pollen = PollenType.values().mapNotNull { type -> grains(type, i)?.let { type to it } }.toMap()
        AirPoint(base.plusHours(i.toLong()), 20.0, 5.0, 10.0, 1.0, pollen)
    }

    private fun forecast(air: List<AirPoint>) = synthetic(hours(base, 72) { this }).copy(air = air)

    @Test
    fun `levels follow the allergy bureau classes`() {
        assertEquals(PollenLevel.NONE, PollenType.GRASS.level(0.4))
        assertEquals(PollenLevel.LOW, PollenType.GRASS.level(3.0))
        assertEquals(PollenLevel.MODERATE, PollenType.GRASS.level(12.0))
        assertEquals(PollenLevel.HIGH, PollenType.GRASS.level(60.0))
        assertEquals(PollenLevel.VERY_HIGH, PollenType.GRASS.level(250.0))
        // The same count means less for trees than for grass.
        assertEquals(PollenLevel.LOW, PollenType.BIRCH.level(12.0))
        assertEquals(PollenLevel.HIGH, PollenType.OLIVE.level(120.0))
        assertEquals(PollenLevel.MODERATE, PollenType.RAGWEED.level(30.0))
    }

    @Test
    fun `daily level uses the mean and remembers the peak hour`() {
        // Grass peaks in the afternoon of the first day: 10 grains, 80 between 12:00 and 17:00.
        val air = air { type, i -> if (type == PollenType.GRASS) (if (i in 12..17) 80.0 else 10.0) else 0.0 }
        val day = Allergy.day(air, PollenType.GRASS, now.toLocalDate())!!
        assertEquals((6 * 80.0 + 18 * 10.0) / 24, day.mean, 1e-9)
        assertEquals(PollenLevel.HIGH, day.level)
        assertEquals(base.plusHours(12), day.peakTime)
        // From late afternoon on, the peak of the day has passed.
        val late = Allergy.day(air, PollenType.GRASS, now.toLocalDate(), base.plusHours(19))!!
        assertFalse(late.peakAhead)
        val morning = Allergy.day(air, PollenType.GRASS, now.toLocalDate(), base.plusHours(9))!!
        assertTrue(morning.peakAhead)
        assertEquals(base.plusHours(12), morning.peakTime)
        // Too few hours of data do not make a day.
        val evening = air { type, i -> if (type == PollenType.GRASS && i in 20..23) 50.0 else null }
        assertNull(Allergy.day(evening, PollenType.GRASS, now.toLocalDate()))
    }

    @Test
    fun `only the user's allergens raise warnings`() {
        val air = air { type, i ->
            when (type) {
                PollenType.GRASS -> if (i < 24) 60.0 else 2.0 // high today
                PollenType.BIRCH -> if (i in 24..47) 2000.0 else 5.0 // very high tomorrow
                else -> 0.0
            }
        }
        val forecast = forecast(air)
        assertTrue(AlertEngine.evaluate(forecast, now).none { it.type == AlertType.POLLEN })

        val grassOnly = AlertEngine.evaluate(forecast, now, AllergyProfile(setOf(PollenType.GRASS))).filter { it.type == AlertType.POLLEN }
        assertEquals(1, grassOnly.size)
        val today = grassOnly.single()
        assertEquals(Severity.YELLOW, today.severity)
        assertEquals("Yüksek polen · çim", today.title)
        assertEquals(base, today.start)
        assertTrue(today.allDay)
        assertFalse(today.outlook)
        assertTrue(today.detail, today.detail.contains("~60 tane/m³"))

        val both = AlertEngine.evaluate(forecast, now, AllergyProfile.ALL).filter { it.type == AlertType.POLLEN }
        val tomorrow = both.single { it.start == base.plusDays(1) }
        assertEquals(Severity.ORANGE, tomorrow.severity)
        assertEquals("Çok yüksek polen · huş", tomorrow.title)
        assertTrue(tomorrow.advice.any { it.contains("Astım") })
    }

    @Test
    fun `summary leads with the allergens and warns about tomorrow`() {
        val air = air { type, i ->
            when (type) {
                PollenType.OLIVE -> if (i < 24) 200.0 else 10.0 // high today, but not an allergen
                PollenType.GRASS -> if (i < 24) 2.0 else 30.0 // low today, high tomorrow
                else -> 0.0
            }
        }
        val summary = Allergy.summary(forecast(air), now, AllergyProfile(setOf(PollenType.GRASS)))
        assertTrue(summary.hasData)
        assertEquals(PollenType.GRASS, summary.types.first().type)
        assertEquals("Alerjiniz olan polenler bugün düşük", summary.headline)
        assertEquals("Yarın çim yüksek", summary.detail)
        assertEquals(3, summary.dates.size)

        val everyone = Allergy.summary(forecast(air), now, null)
        assertEquals("Bugün zeytin poleni yüksek", everyone.headline)
        assertTrue(everyone.detail!!, everyone.detail!!.startsWith("En yoğun"))
    }

    @Test
    fun `no pollen data is said plainly`() {
        val summary = Allergy.summary(forecast(air { _, _ -> null }), now, AllergyProfile.ALL)
        assertFalse(summary.hasData)
        assertEquals("Bu konum için polen tahmini yok", summary.headline)
        assertTrue(AlertEngine.evaluate(forecast(air { _, _ -> null }), now, AllergyProfile.ALL).none { it.type == AlertType.POLLEN })
    }

    @Test
    fun `allergy tip and morning summary`() {
        val air = air { type, i -> if (type == PollenType.GRASS) 30.0 else 0.0 }
        val forecast = forecast(air)
        val profile = AllergyProfile(setOf(PollenType.GRASS))
        assertTrue(Insights.tips(forecast, now).none { it.kind == TipKind.ALLERGY })
        val tip = Insights.tips(forecast, now, profile).single { it.kind == TipKind.ALLERGY }
        assertEquals("Alerji ilacı (çim poleni yüksek)", tip.text)
        val morning = Insights.morningSummary(forecast, now, profile)
        assertTrue(morning, morning.contains("Bugün çim poleni yüksek"))
        assertTrue(morning, morning.contains("alerji ilacı"))
    }

    @Test
    fun `ensemble gives warnings a probability`() {
        val rainy = hours(base, 48) { i ->
            if (i in 16..18) copy(precipitation = 4.0, rain = 4.0, precipitationProbability = 80, weatherCode = 63) else this
        }
        // 40 of 50 members rain in the afternoon, 10 stay dry.
        val ensemble = (0 until 50).map { member ->
            ModelRun("member$member", (0 until 48).map { i ->
                ModelHour(base.plusHours(i.toLong()), if (member < 40 && i in 16..18) 3.0 else 0.0, 20.0, 12.0)
            })
        }
        val alert = AlertEngine.evaluate(synthetic(rainy).copy(ensemble = ensemble), now).single { it.type == AlertType.RAIN }
        assertEquals(Confidence(0, 0, 80), alert.confidence)
        assertEquals(Confidence.Level.HIGH, alert.confidence!!.level)
        assertEquals("Güven: Yüksek · olasılık %80", alert.confidence!!.text)

        // With the models too, both show; the ensemble decides the level.
        val models = listOf("a", "b", "c").map { name ->
            ModelRun(name, (0 until 48).map { i -> ModelHour(base.plusHours(i.toLong()), if (i in 16..18) 4.0 else 0.0, 20.0, 12.0) })
        }
        val both = AlertEngine.evaluate(synthetic(rainy).copy(ensemble = ensemble.take(20), models = models), now)
            .single { it.type == AlertType.RAIN }
        assertEquals(Confidence(3, 3, 100), both.confidence)
        assertEquals("Güven: Yüksek · olasılık %100 · 3/3 model", both.confidence!!.text)
    }
}
