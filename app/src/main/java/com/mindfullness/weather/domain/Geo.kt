package com.mindfullness.weather.domain

import kotlin.math.cos
import kotlin.math.sqrt

object Geo {
    /** Approximate distance in km (equirectangular), good enough within a few hundred km. */
    fun distanceKm(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val meanLat = Math.toRadians((latitude1 + latitude2) / 2)
        val dx = Math.toRadians(longitude2 - longitude1) * cos(meanLat)
        val dy = Math.toRadians(latitude2 - latitude1)
        return sqrt(dx * dx + dy * dy) * 6371.0
    }
}
