package com.mindfullness.weather.domain

import org.threeten.bp.LocalDate
import java.util.Locale

/**
 * A city's official pollen bulletin: counts from a volumetric trap, identified under the microscope
 * by a university's palynologists and published together with the Turkish State Meteorological
 * Service (MGM). It covers many more types than the model forecast (e.g. cypress, pine, plane tree,
 * goosefoot), so it is shown next to it for places near the trap.
 */
data class PollenBulletin(
    val id: String,
    val name: String,
    /** For tight spaces: "Ankara bülteni". */
    val shortName: String,
    val source: String,
    val url: String,
    val latitude: Double,
    val longitude: Double,
)

object PollenBulletins {
    /** The counts describe one city's air; well beyond its outskirts they say little. */
    const val RADIUS_KM = 60.0

    val ANKARA = PollenBulletin(
        "ankara", "Ankara Alerjik Polen Bülteni", "Ankara bülteni", "Ankara Üniversitesi ve MGM ölçümleri",
        "https://polenalerji.ankara.edu.tr/", 39.93, 32.86,
    )
    val IZMIR = PollenBulletin(
        "izmir", "İzmir Polen Alerji Bülteni", "İzmir bülteni", "Ege Üniversitesi ve MGM ölçümleri",
        "https://polenalerji.ege.edu.tr/", 38.46, 27.22,
    )
    val ALL = listOf(ANKARA, IZMIR)

    fun byId(id: String): PollenBulletin? = ALL.firstOrNull { it.id == id }

    fun near(latitude: Double, longitude: Double): PollenBulletin? =
        ALL.map { it to Geo.distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= RADIUS_KM }
            .minByOrNull { it.second }
            ?.first
}

/** The pollen groups the bulletins report, in their own words. */
enum class PollenGroup(val label: String) {
    TREE("Ağaç ve çalı"),
    WEED("Yabani ot"),
    GRASS("Çayır"),
}

data class BulletinDay(val date: LocalDate, val level: PollenLevel)

data class BulletinGroup(
    val group: PollenGroup,
    /** Level per day of a daily bulletin (Ankara: today and the next two days). */
    val days: List<BulletinDay> = emptyList(),
    /** Level of a weekly bulletin (İzmir), measured over [BulletinReading.measuredFrom]–[BulletinReading.measuredTo]. */
    val weekLevel: PollenLevel? = null,
    /** The dominant allergens as the bulletin names them, e.g. "Pelin otu (Artemisia, Mugworth)". */
    val dominant: List<String> = emptyList(),
) {
    fun on(date: LocalDate): PollenLevel? = days.firstOrNull { it.date == date }?.level

    /** Dominant allergens without the Latin and English names: "Pelin otu". */
    val dominantNames: List<String> get() = dominant.map { it.substringBefore(" (").trim() }.filter { it.isNotEmpty() }

    /** Whether one of the dominant allergens is [type]. */
    fun names(type: PollenType): Boolean {
        val keys = KEYWORDS[type] ?: return false
        return dominant.any { name -> name.lowercase(TR).let { lower -> keys.any { it in lower } } }
    }

    private companion object {
        val TR: Locale = Locale.forLanguageTag("tr-TR")
        val KEYWORDS = mapOf(
            PollenType.ALDER to listOf("kızılağaç", "alnus"),
            PollenType.BIRCH to listOf("huş", "betula"),
            PollenType.OLIVE to listOf("zeytin", "olea"),
            PollenType.GRASS to listOf("buğdaygil", "poaceae", "çayır", "çimen"),
            PollenType.MUGWORT to listOf("pelin", "artemisia"),
            PollenType.RAGWEED to listOf("ambrosia", "kanarya"),
        )
    }
}

/** What a city bulletin said when it was last read. */
data class BulletinReading(
    val bulletin: PollenBulletin,
    val groups: List<BulletinGroup>,
    val fetchedAtMillis: Long,
    /** The week a weekly bulletin's counts were measured; null for a daily one. */
    val measuredFrom: LocalDate? = null,
    val measuredTo: LocalDate? = null,
    /** A weekly bulletin's chance of being affected in [riskHours] of [riskDate], from the counts and the weather. */
    val risk: PollenLevel? = null,
    val riskDate: LocalDate? = null,
    val riskHours: String? = null,
) {
    val isWeekly: Boolean get() = measuredTo != null

    /**
     * Whether the bulletin says something about [today]: a daily one has a level for it, a weekly
     * one was measured recently. İzmir's page keeps showing its last week out of season, for months.
     */
    fun isCurrent(today: LocalDate): Boolean {
        val week = measuredTo
        return if (week != null) !week.isBefore(today.minusDays(STALE_AFTER_DAYS))
        else groups.any { group -> group.days.any { it.date == today } }
    }

    /** Today's level of each group: the day's forecast, or the week's count while it is recent. */
    fun levelsOn(today: LocalDate): List<Pair<BulletinGroup, PollenLevel>> {
        if (!isCurrent(today)) return emptyList()
        return groups.mapNotNull { group -> (if (isWeekly) group.weekLevel else group.on(today))?.let { group to it } }
    }

    companion object {
        /** A week of counts older than this no longer describes the air. */
        const val STALE_AFTER_DAYS = 14L
    }
}
