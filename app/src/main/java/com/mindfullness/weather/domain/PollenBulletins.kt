package com.mindfullness.weather.domain

/**
 * A city's official pollen bulletin: counts from a volumetric trap, identified under the microscope
 * by a university's palynologists and published together with the Turkish State Meteorological
 * Service (MGM). It covers many more types than the model forecast (e.g. cypress, pine, plane tree,
 * goosefoot), so it is offered next to it for places near the trap.
 */
data class PollenBulletin(
    val name: String,
    val source: String,
    val url: String,
    val latitude: Double,
    val longitude: Double,
)

object PollenBulletins {
    /** The counts describe one city's air; well beyond its outskirts they say little. */
    const val RADIUS_KM = 60.0

    val ALL = listOf(
        PollenBulletin(
            "Ankara Alerjik Polen Bülteni", "Ankara Üniversitesi ve MGM ölçümleri",
            "https://polenalerji.ankara.edu.tr/", 39.93, 32.86,
        ),
        PollenBulletin(
            "İzmir Polen Alerji Bülteni", "Ege Üniversitesi ve MGM ölçümleri",
            "https://polenalerji.ege.edu.tr/", 38.46, 27.22,
        ),
    )

    fun near(latitude: Double, longitude: Double): PollenBulletin? =
        ALL.map { it to Geo.distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= RADIUS_KM }
            .minByOrNull { it.second }
            ?.first
}
