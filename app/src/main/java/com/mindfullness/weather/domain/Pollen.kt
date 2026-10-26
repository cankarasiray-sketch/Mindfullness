package com.mindfullness.weather.domain

/**
 * Pollen types forecast by CAMS (Europe, which includes most of Turkey), with the count classes of
 * the US National Allergy Bureau, in grains/m³ averaged over a day.
 */
enum class PollenType(
    /** Open-Meteo variable name. */
    val apiName: String,
    val label: String,
    val group: Group,
) {
    ALDER("alder_pollen", "Kızılağaç", Group.TREE),
    BIRCH("birch_pollen", "Huş", Group.TREE),
    OLIVE("olive_pollen", "Zeytin", Group.TREE),
    GRASS("grass_pollen", "Çim ve çayır otları", Group.GRASS),
    MUGWORT("mugwort_pollen", "Pelin otu", Group.WEED),
    RAGWEED("ragweed_pollen", "Ambrosia (kanarya otu)", Group.WEED),
    ;

    /** Shorter name for tight spaces. */
    val shortLabel: String get() = label.substringBefore(" (").substringBefore(" ve ")

    fun level(grains: Double?): PollenLevel {
        if (grains == null || grains < 1) return PollenLevel.NONE
        val (moderate, high, veryHigh) = group.limits
        return when {
            grains >= veryHigh -> PollenLevel.VERY_HIGH
            grains >= high -> PollenLevel.HIGH
            grains >= moderate -> PollenLevel.MODERATE
            else -> PollenLevel.LOW
        }
    }

    enum class Group(val label: String, val limits: Triple<Double, Double, Double>) {
        TREE("Ağaçlar", Triple(15.0, 90.0, 1500.0)),
        GRASS("Çimenler", Triple(5.0, 20.0, 200.0)),
        WEED("Yabani otlar", Triple(10.0, 50.0, 500.0)),
    }
}

enum class PollenLevel(val label: String) {
    NONE("Yok"),
    LOW("Düşük"),
    MODERATE("Orta"),
    HIGH("Yüksek"),
    VERY_HIGH("Çok yüksek"),
}

/** The pollen a user is allergic to; null elsewhere means "no allergy", so no pollen warnings. */
data class AllergyProfile(val types: Set<PollenType>) {
    companion object {
        val ALL = AllergyProfile(PollenType.values().toSet())
    }
}
