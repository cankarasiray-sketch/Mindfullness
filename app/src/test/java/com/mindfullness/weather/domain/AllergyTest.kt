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
        assertEquals("Şu an polen tahmini yok", summary.headline)
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
    fun `day-long alerts are announced in the evening before or by day, not at night`() {
        val air = air { type, i -> if (type == PollenType.GRASS && i in 24..47) 60.0 else 0.0 }
        val tomorrow = AlertEngine.evaluate(forecast(air), now, AllergyProfile.ALL).single { it.start == base.plusDays(1) }
        fun due(hour: Int, minute: Int = 0) = NotificationPolicy.isDue(tomorrow, base.withHour(hour).withMinute(minute))
        assertFalse(due(9)) // a day early
        assertTrue(due(18, 30)) // the evening before
        assertFalse(due(23)) // never at night
        val todays = tomorrow.copy(start = base, end = base.plusDays(1))
        assertFalse(NotificationPolicy.isDue(todays, base.withHour(0).withMinute(20)))
        assertTrue(NotificationPolicy.isDue(todays, base.withHour(7)))
        // An urgent day-long warning (e.g. orange flood risk) does not wait for the morning.
        val flood = todays.copy(type = AlertType.FLOOD, severity = Severity.ORANGE)
        assertTrue(NotificationPolicy.isDue(flood, base.withHour(2)))
        // Hourly hazards: 24 hours ahead, any time.
        val storm = todays.copy(type = AlertType.WIND, allDay = false, start = base.plusHours(20))
        assertTrue(NotificationPolicy.isDue(storm, base.withHour(1)))
    }

    @Test
    fun `turkish possessive after numbers`() {
        val expected = mapOf(0 to "'ı", 1 to "'i", 2 to "'si", 3 to "'ü", 6 to "'sı", 9 to "'u", 10 to "'u", 30 to "'u", 40 to "'ı",
            50 to "'si", 60 to "'ı", 78 to "'i", 80 to "'i", 90 to "'ı", 100 to "'ü")
        expected.forEach { (n, suffix) -> assertEquals("$n", suffix, TurkishText.possessive(n)) }
    }

    @Test
    fun `headline says when the allergens are out of season`() {
        val air = air { type, _ -> if (type == PollenType.RAGWEED) 30.0 else null }
        val summary = Allergy.summary(forecast(air), now, AllergyProfile(setOf(PollenType.OLIVE)))
        assertEquals("Alerjiniz olan polenler şu an görülmüyor", summary.headline)
        assertTrue(summary.hasData)
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
        assertEquals(Confidence(0, 0, 50, 40), alert.confidence)
        assertEquals(80, alert.confidence!!.probability)
        assertEquals(Confidence.Level.HIGH, alert.confidence!!.level)
        assertEquals("Güven: Yüksek · 50 senaryonun 40'ı", alert.confidence!!.text)

        // With the models too, both show; the ensemble decides the level.
        val models = listOf("a", "b", "c").map { name ->
            ModelRun(name, (0 until 48).map { i -> ModelHour(base.plusHours(i.toLong()), if (i in 16..18) 4.0 else 0.0, 20.0, 12.0) })
        }
        val both = AlertEngine.evaluate(synthetic(rainy).copy(ensemble = ensemble.take(20), models = models), now)
            .single { it.type == AlertType.RAIN }
        assertEquals(Confidence(3, 3, 20, 20), both.confidence)
        assertEquals("Güven: Yüksek · 20 senaryonun tamamı · 3/3 model", both.confidence!!.text)
        // Few members agreeing lowers the level even when the models agree.
        assertEquals("Güven: Düşük · 51 senaryonun 6'sı · 3/3 model", Confidence(3, 3, 51, 6).text)
        assertEquals("Güven: Düşük · 51 senaryonun hiçbiri", Confidence(0, 0, 51, 0).text)
    }

    @Test
    fun `thunderstorms in the grass season warn about storm asthma`() {
        val stormy = hours(base, 72) { i -> if (i == 16) copy(weatherCode = 95, precipitation = 6.0, rain = 6.0) else this }
        fun stormAdvice(grass: Double, profile: AllergyProfile?): List<String> {
            val forecast = synthetic(stormy).copy(air = air { type, _ -> if (type == PollenType.GRASS) grass else 0.0 })
            return AlertEngine.evaluate(forecast, now, profile).single { it.type == AlertType.THUNDERSTORM }.advice
        }
        val grass = AllergyProfile(setOf(PollenType.GRASS))
        val advice = stormAdvice(30.0, grass)
        assertEquals(1, advice.count { it.contains("Çim poleni") })
        assertTrue(advice.last(), advice.last().startsWith("Çim poleni yüksek") && advice.last().endsWith("astım atağını tetikleyebilir."))
        assertTrue(stormAdvice(8.0, grass).any { it.startsWith("Çim poleni orta") })
        // Low grass pollen, no allergy, or another allergen: the usual storm advice only.
        assertTrue(stormAdvice(2.0, grass).none { it.contains("astım") })
        assertTrue(stormAdvice(30.0, null).none { it.contains("astım") })
        assertTrue(stormAdvice(30.0, AllergyProfile(setOf(PollenType.BIRCH))).none { it.contains("astım") })
    }

    @Test
    fun `local bulletins are offered near their city only`() {
        assertEquals("Ankara Alerjik Polen Bülteni", PollenBulletins.near(39.97, 33.11)?.name) // Lalahan, Mamak
        assertEquals("İzmir Polen Alerji Bülteni", PollenBulletins.near(38.42, 27.14)?.name) // Konak
        assertNull(PollenBulletins.near(41.01, 28.98)) // İstanbul
        assertNull(PollenBulletins.near(39.58, 32.15)) // Polatlı, ~75 km from Ankara
    }

    /** Ankara's bulletin as read on the test's day: trees and weeds high, grass low. */
    private fun ankara(fetchedAt: LocalDateTime = now) = BulletinReading(
        PollenBulletins.ANKARA,
        listOf(
            BulletinGroup(PollenGroup.TREE, days = (0L..2L).map { BulletinDay(base.toLocalDate().plusDays(it), PollenLevel.HIGH) },
                dominant = listOf("Çamgiller (Pinaceae, The Pine family)", "Servigiller (Cupressaceae, Cypress)")),
            BulletinGroup(PollenGroup.WEED, days = (0L..2L).map { BulletinDay(base.toLocalDate().plusDays(it), PollenLevel.HIGH) },
                dominant = listOf("Pelin otu (Artemisia, Mugworth)", "Horozibiğigiller, Ispanakgiller (Amaranthaceae, The Amaranth family)")),
            BulletinGroup(PollenGroup.GRASS, days = (0L..2L).map { BulletinDay(base.toLocalDate().plusDays(it), PollenLevel.LOW) },
                dominant = listOf("Buğdaygiller (Poaceae, Grasses)")),
        ),
        fetchedAtMillis = fetchedAt.toInstant(org.threeten.bp.ZoneOffset.ofHours(3)).toEpochMilli(),
    )

    @Test
    fun `the city bulletin speaks where the forecast has nothing`() {
        val forecast = forecast(air { _, _ -> null }).copy(bulletin = ankara())
        val mugwort = AllergyProfile(setOf(PollenType.MUGWORT))
        val summary = Allergy.summary(forecast, now, mugwort)
        assertTrue(summary.hasData)
        assertEquals("Bugün pelin otu poleni yüksek", summary.headline)
        assertEquals("Kaynak: Ankara Alerjik Polen Bülteni", summary.detail)
        assertEquals(PollenLevel.HIGH, summary.todayLevel)
        assertEquals("Alerji ilacı (pelin otu poleni yüksek)", Insights.tips(forecast, now, mugwort).single { it.kind == TipKind.ALLERGY }.text)

        // Three days alike make one warning.
        val today = AlertEngine.evaluate(forecast, now, mugwort).single { it.type == AlertType.POLLEN }
        assertEquals(base, today.start)
        assertEquals(base.plusDays(3), today.end)
        assertEquals("Bugün – Per", today.whenText(now))
        assertEquals("Yüksek polen · pelin otu", today.title)
        assertEquals("Ankara bülteni: yabani ot yüksek", today.detail)

        // Trees are high too, but birch is not among them: nothing to warn a birch allergy about.
        val birch = AllergyProfile(setOf(PollenType.BIRCH))
        assertEquals("Alerjiniz olan polenler şu an görülmüyor", Allergy.summary(forecast, now, birch).headline)
        assertTrue(AlertEngine.evaluate(forecast, now, birch).none { it.type == AlertType.POLLEN })
        // Grass is named but low.
        assertEquals("Alerjiniz olan polenler bugün düşük", Allergy.summary(forecast, now, AllergyProfile(setOf(PollenType.GRASS))).headline)

        val everyone = Allergy.summary(forecast, now, null)
        assertEquals("Bugün ağaç ve çalı polenleri yüksek", everyone.headline)
        assertEquals("Kaynak: Ankara Alerjik Polen Bülteni", everyone.detail)
    }

    @Test
    fun `the forecast keeps the lead when it reports more, and old bulletins are ignored`() {
        // Model: mugwort very high today; bulletin: weeds high.
        val air = air { type, _ -> if (type == PollenType.MUGWORT) 600.0 else 0.0 }
        val mugwort = AllergyProfile(setOf(PollenType.MUGWORT))
        val both = forecast(air).copy(bulletin = ankara())
        assertEquals("Bugün pelin otu poleni çok yüksek", Allergy.summary(both, now, mugwort).headline)
        val alert = AlertEngine.evaluate(both, now, mugwort).single { it.type == AlertType.POLLEN && it.start == base }
        assertEquals(Severity.ORANGE, alert.severity)
        assertTrue(alert.detail, alert.detail.startsWith("Pelin otu çok yüksek") && alert.detail.contains("Ankara bülteni: yabani ot yüksek"))

        // A reading whose three days have passed, and İzmir's March week in October: as if there were none.
        val old = forecast(air { _, _ -> null }).copy(bulletin = ankara(now.minusDays(3)).let { reading ->
            reading.copy(groups = reading.groups.map { g -> g.copy(days = g.days.map { it.copy(date = it.date.minusDays(3)) }) })
        })
        assertEquals("Şu an polen tahmini yok", Allergy.summary(old, now, mugwort).headline)
        val march = BulletinReading(
            PollenBulletins.IZMIR, listOf(BulletinGroup(PollenGroup.WEED, weekLevel = PollenLevel.HIGH, dominant = listOf("Pelin otu (Artemisia)"))),
            fetchedAtMillis = 0, measuredFrom = now.toLocalDate().minusDays(60), measuredTo = now.toLocalDate().minusDays(54),
        )
        val stale = forecast(air { _, _ -> null }).copy(bulletin = march)
        assertEquals("Şu an polen tahmini yok", Allergy.summary(stale, now, mugwort).headline)
        assertTrue(AlertEngine.evaluate(stale, now, mugwort).none { it.type == AlertType.POLLEN })
        // The same week while recent counts for today only.
        val recent = stale.copy(bulletin = march.copy(measuredFrom = now.toLocalDate().minusDays(6), measuredTo = now.toLocalDate()))
        assertEquals(listOf(base), AlertEngine.evaluate(recent, now, mugwort).filter { it.type == AlertType.POLLEN }.map { it.start })
    }

    @Test
    fun `zero pollen reads as none, not low`() {
        val zero = forecast(air { _, _ -> 0.0 })
        assertEquals("Alerjiniz olan polenler bugün havada yok", Allergy.summary(zero, now, AllergyProfile.ALL).headline)
        assertEquals("Bugün havada polen yok", Allergy.summary(zero, now, null).headline)
        // As in Ankara in late October: the model has nothing, the bulletin measures weeds high.
        assertEquals("Bugün pelin otu poleni yüksek", Allergy.summary(zero.copy(bulletin = ankara()), now, AllergyProfile.ALL).headline)
    }
}
