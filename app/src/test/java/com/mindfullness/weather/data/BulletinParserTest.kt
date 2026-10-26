package com.mindfullness.weather.data

import com.mindfullness.weather.domain.PollenBulletins
import com.mindfullness.weather.domain.PollenGroup
import com.mindfullness.weather.domain.PollenLevel
import com.mindfullness.weather.domain.PollenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneOffset

/** Against excerpts of the real pages, as served on 26.10.2026. */
class BulletinParserTest {
    private val ankaraHtml = javaClass.getResource("/bulletin_ankara.html")!!.readText()
    private val izmirHtml = javaClass.getResource("/bulletin_izmir.html")!!.readText()

    /** 26.10.2026 17:30 in Turkey. */
    private val fetchedAt = LocalDateTime.of(2026, 10, 26, 17, 30).toInstant(ZoneOffset.ofHours(3)).toEpochMilli()
    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun `ankara gives three days per group with the dominant allergens`() {
        val reading = BulletinParser.parse(PollenBulletins.ANKARA, ankaraHtml, fetchedAt)!!
        assertEquals(listOf(PollenGroup.TREE, PollenGroup.WEED, PollenGroup.GRASS), reading.groups.map { it.group })
        val trees = reading.groups[0]
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), trees.days.map { it.date })
        assertEquals(PollenLevel.HIGH, trees.on(today))
        assertEquals(listOf("Çamgiller", "Servigiller"), trees.dominantNames)
        val weeds = reading.groups[1]
        assertEquals(PollenLevel.HIGH, weeds.on(today.plusDays(2)))
        assertEquals(listOf("Pelin otu", "Horozibiğigiller, Ispanakgiller"), weeds.dominantNames)
        assertTrue(weeds.names(PollenType.MUGWORT))
        assertFalse(weeds.names(PollenType.RAGWEED))
        val grass = reading.groups[2]
        assertEquals(PollenLevel.LOW, grass.on(today))
        assertTrue(grass.names(PollenType.GRASS))

        assertFalse(reading.isWeekly)
        assertTrue(reading.isCurrent(today))
        assertEquals(3, reading.levelsOn(today).size)
        // The reading covers its three days; once they have passed it says nothing about today.
        assertTrue(reading.isCurrent(today.plusDays(2)))
        assertFalse(reading.isCurrent(today.plusDays(3)))
    }

    @Test
    fun `izmir gives the measured week and the hours' risk`() {
        val reading = BulletinParser.parse(PollenBulletins.IZMIR, izmirHtml, fetchedAt)!!
        assertTrue(reading.isWeekly)
        assertEquals(LocalDate.of(2026, 3, 23), reading.measuredFrom)
        assertEquals(LocalDate.of(2026, 3, 29), reading.measuredTo)
        assertEquals(PollenLevel.LOW, reading.risk)
        assertEquals(today, reading.riskDate)
        assertEquals("15:00–18:00", reading.riskHours)
        assertEquals(listOf(PollenGroup.TREE, PollenGroup.WEED, PollenGroup.GRASS), reading.groups.map { it.group })
        assertEquals(listOf(PollenLevel.HIGH, PollenLevel.HIGH, PollenLevel.MODERATE), reading.groups.map { it.weekLevel })
        assertEquals(listOf("Servi", "Çınar"), reading.groups[0].dominantNames)
        assertEquals(listOf("Isırganotugiller"), reading.groups[1].dominantNames)
        assertTrue(reading.groups[2].names(PollenType.GRASS))
        // Counts from March say nothing about late October.
        assertFalse(reading.isCurrent(today))
        assertTrue(reading.levelsOn(today).isEmpty())
        assertTrue(reading.isCurrent(LocalDate.of(2026, 4, 5)))
    }

    @Test
    fun `unexpected pages give nothing`() {
        assertNull(BulletinParser.parse(PollenBulletins.ANKARA, "<html><body>Bakımdayız</body></html>", fetchedAt))
        assertNull(BulletinParser.parse(PollenBulletins.IZMIR, "", fetchedAt))
        assertNull(BulletinParser.parse(PollenBulletins.ANKARA, izmirHtml, fetchedAt))
        assertNull(BulletinParser.parse(PollenBulletins.IZMIR, ankaraHtml, fetchedAt))
    }
}
