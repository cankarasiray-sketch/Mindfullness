package com.mindfullness.weather.domain

/** Visual/semantic grouping of WMO weather codes used by Open-Meteo. */
enum class Condition {
    CLEAR,
    MOSTLY_CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    RAIN,
    HEAVY_RAIN,
    FREEZING_RAIN,
    SNOW,
    SLEET,
    SHOWERS,
    THUNDERSTORM,
    HAIL,
}

object WeatherCodes {
    val FREEZING = setOf(56, 57, 66, 67)
    val THUNDER = setOf(95, 96, 99)
    val HAIL = setOf(96, 99)
    val FOG = setOf(45, 48)
    val SNOW = setOf(71, 73, 75, 77, 85, 86)

    fun condition(code: Int): Condition = when (code) {
        0 -> Condition.CLEAR
        1 -> Condition.MOSTLY_CLEAR
        2 -> Condition.PARTLY_CLOUDY
        3 -> Condition.OVERCAST
        45, 48 -> Condition.FOG
        51, 53, 55 -> Condition.DRIZZLE
        56, 57, 66, 67 -> Condition.FREEZING_RAIN
        61, 63 -> Condition.RAIN
        65 -> Condition.HEAVY_RAIN
        71, 73, 75, 77, 85, 86 -> Condition.SNOW
        80, 81 -> Condition.SHOWERS
        82 -> Condition.HEAVY_RAIN
        95 -> Condition.THUNDERSTORM
        96, 99 -> Condition.HAIL
        else -> Condition.PARTLY_CLOUDY
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Açık"
        1 -> "Çoğunlukla açık"
        2 -> "Parçalı bulutlu"
        3 -> "Kapalı"
        45 -> "Sisli"
        48 -> "Kırağılı sis"
        51 -> "Hafif çisenti"
        53 -> "Çisenti"
        55 -> "Yoğun çisenti"
        56 -> "Dondurucu çisenti"
        57 -> "Yoğun dondurucu çisenti"
        61 -> "Hafif yağmurlu"
        63 -> "Yağmurlu"
        65 -> "Şiddetli yağmur"
        66 -> "Dondurucu yağmur"
        67 -> "Şiddetli dondurucu yağmur"
        71 -> "Hafif kar"
        73 -> "Kar yağışlı"
        75 -> "Yoğun kar"
        77 -> "Kar taneleri"
        80 -> "Hafif sağanak"
        81 -> "Sağanak yağış"
        82 -> "Şiddetli sağanak"
        85 -> "Hafif kar sağanağı"
        86 -> "Yoğun kar sağanağı"
        95 -> "Gök gürültülü sağanak"
        96 -> "Hafif dolulu fırtına"
        99 -> "Dolulu fırtına"
        else -> "Bilinmiyor"
    }
}
