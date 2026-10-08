package com.mindfullness.weather.domain

import java.time.LocalDateTime
import kotlin.math.roundToInt

enum class TipKind { UMBRELLA, WARM_COAT, JACKET, SUNSCREEN, SUNGLASSES, WATER, BOOTS, WINDBREAKER }

data class Tip(val kind: TipKind, val text: String)

/** One-line rain outlook for the next hours, shown above the precipitation chart. */
data class PrecipitationSummary(
    val headline: String,
    val detail: String?,
    val expectsPrecipitation: Boolean,
)

object Insights {
    private const val TIP_HOURS = 15
    private const val PRECIP_HOURS = 24

    /** What to take along for the rest of the day. */
    fun tips(forecast: Forecast, now: LocalDateTime): List<Tip> {
        val hours = forecast.hoursFrom(now, TIP_HOURS)
        if (hours.isEmpty()) return emptyList()
        val precipitationHours = forecast.precipitationHours(now, TIP_HOURS)
        val minFeels = hours.minOf { it.apparentTemperature }
        val maxFeels = hours.maxOf { it.apparentTemperature }
        val maxUv = hours.filter { it.isDay }.maxOfOrNull { it.uvIndex ?: 0.0 } ?: 0.0
        val maxGust = hours.maxOf { it.windGusts }
        val wet = precipitationHours.filter { AlertEngine.isWet(it) }
        val rainy = wet.filter { !AlertEngine.isSnowy(it) || it.liquid >= 0.2 }
        val snowy = precipitationHours.any { AlertEngine.isSnowy(it) }
        val sunnyDaytime = hours.count { it.isDay && it.weatherCode <= 1 } >= 3

        return buildList {
            if (rainy.isNotEmpty()) {
                val probability = rainy.mapNotNull { it.precipitationProbability }.maxOrNull()
                add(Tip(TipKind.UMBRELLA, if (probability != null) "Şemsiye (%$probability)" else "Şemsiye"))
            }
            if (snowy || precipitationHours.any { AlertEngine.isFreezing(it) }) {
                add(Tip(TipKind.BOOTS, "Kaymaz ayakkabı"))
            }
            when {
                minFeels < 5 -> add(Tip(TipKind.WARM_COAT, "Kalın mont, bere, eldiven"))
                minFeels < 12 -> add(Tip(TipKind.WARM_COAT, "Mont"))
                minFeels < 18 -> add(Tip(TipKind.JACKET, "Hırka veya ceket"))
            }
            if (maxGust >= 45 && minFeels >= 12) add(Tip(TipKind.WINDBREAKER, "Rüzgarlık"))
            if (maxUv >= 6) add(Tip(TipKind.SUNSCREEN, "Güneş kremi (UV ${maxUv.roundToInt()})"))
            if (sunnyDaytime && maxUv >= 3) add(Tip(TipKind.SUNGLASSES, "Güneş gözlüğü"))
            if (maxFeels >= 30) add(Tip(TipKind.WATER, "Su şişesi"))
        }
    }

    fun precipitation(forecast: Forecast, now: LocalDateTime): PrecipitationSummary {
        // Each point holds the amount of the hour before it (see Forecast.precipitationHours).
        val hours = forecast.precipitationHours(now, PRECIP_HOURS)
        if (hours.isEmpty()) {
            return PrecipitationSummary("Güncel yağış tahmini yok", "Verileri yenilemek için internete bağlanın", false)
        }
        val firstWet = hours.indexOfFirst { AlertEngine.isWet(it) }
        if (firstWet < 0) {
            val maxProbability = hours.mapNotNull { it.precipitationProbability }.maxOrNull() ?: 0
            return PrecipitationSummary(
                headline = "Önümüzdeki ${hours.size} saatte yağış beklenmiyor",
                detail = if (maxProbability >= 20) "En yüksek yağış olasılığı %$maxProbability" else null,
                expectsPrecipitation = false,
            )
        }
        // Follow the episode past the 24 h chart so its end and total are not cut off.
        val longer = forecast.precipitationHours(now, PRECIP_HOURS * 2)
        val episode = AlertEngine.episodes(longer.drop(firstWet)) { AlertEngine.isWet(it) }.first()
        val total = episode.sumOf { it.precipitation }
        val probability = episode.mapNotNull { it.precipitationProbability }.maxOrNull()
        val snow = episode.count { AlertEngine.isSnowy(it) } * 2 > episode.size
        val kind = if (snow) "Kar" else "Yağış"
        val start = episode.first().time.minusHours(1)
        val end = episode.last().time

        val headline = when {
            firstWet == 0 && episode.first().precipitation >= 0.1 -> "$kind sürüyor · tahmini bitiş ${clock(end, now)}"
            firstWet == 0 -> "$kind olasılığı yüksek · şimdi – ${clock(end, now)}"
            else -> "$kind bekleniyor · ${clock(start, now)} – ${clock(end, now)}"
        }
        val detail = buildList {
            if (total >= 0.1) add(if (snow) "~${AlertEngine.formatAmount(episode.sumOf { it.snowfall })} cm" else "~${AlertEngine.formatAmount(total)} mm")
            if (probability != null) add("olasılık %$probability")
            add("${episode.size} saat")
        }.joinToString(" · ")
        return PrecipitationSummary(headline, detail, expectsPrecipitation = true)
    }

    private fun clock(time: LocalDateTime, now: LocalDateTime): String =
        if (time.toLocalDate() == now.toLocalDate()) TimeText.hour(time)
        else "${TimeText.relativeDay(time.toLocalDate(), now.toLocalDate())} ${TimeText.hour(time)}"

    /** Compact sentence for the morning notification. */
    fun morningSummary(forecast: Forecast, now: LocalDateTime): String {
        val today = forecast.today(now)
        val parts = mutableListOf<String>()
        if (today != null) {
            parts += "${WeatherCodes.describe(today.weatherCode)}, ${today.temperatureMin.roundToInt()}° / ${today.temperatureMax.roundToInt()}°"
        }
        val precipitation = precipitation(forecast, now)
        if (precipitation.expectsPrecipitation) parts += precipitation.headline
        val tips = tips(forecast, now)
        if (tips.isNotEmpty()) parts += "Yanınıza alın: " + tips.joinToString(", ") { it.text.substringBefore(" (").lowercase(TR) }
        return parts.joinToString(" · ")
    }

    private val TR = java.util.Locale.forLanguageTag("tr-TR")
}
