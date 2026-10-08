package com.mindfullness.weather.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Warning levels follow the colour scheme used by MGM / Meteoalarm. */
enum class Severity(val label: String, val shortLabel: String) {
    INFO("Bilgi", "Bilgi"),
    YELLOW("Sarı · Dikkatli olun", "Sarı"),
    ORANGE("Turuncu · Hazırlıklı olun", "Turuncu"),
    RED("Kırmızı · Önlem alın", "Kırmızı"),
}

enum class AlertType {
    RAIN, SNOW, THUNDERSTORM, WIND, HEAT, COLD, ICE, FOG, UV, TEMPERATURE_DROP,
}

data class WeatherAlert(
    val type: AlertType,
    val severity: Severity,
    val title: String,
    val start: LocalDateTime,
    /** Exclusive end: the hour after the last affected hour. */
    val end: LocalDateTime,
    val detail: String,
    val advice: List<String>,
    /** True for day-level heads-ups further than 48 hours ahead. */
    val outlook: Boolean = false,
    /** Day-level alert without meaningful start/end hours. */
    val allDay: Boolean = outlook,
) {
    /**
     * Identifies the event across forecast refreshes: episodes are built from the whole day's data,
     * so the start stays the same while the event is in progress. Severity is tracked separately.
     */
    val key: String get() = "${type}_$start"

    /** "Bugün 15:00–21:00", "Şimdi – 18:00" or "Cumartesi, 10 Ekim" for day-level alerts. */
    fun whenText(now: LocalDateTime): String =
        if (allDay) "${TimeText.relativeDay(start.toLocalDate(), now.toLocalDate())}, ${TimeText.dayMonth(start.toLocalDate())}"
        else TimeText.range(start, end, now)
}

/**
 * Turns a raw forecast into actionable warnings: what is coming, when, how serious it is and
 * what to do about it. Pure Kotlin so it can be unit tested and reused by the background worker.
 */
object AlertEngine {
    const val DETAIL_HOURS = 48L
    private const val OUTLOOK_DAYS = 7L

    fun evaluate(forecast: Forecast, now: LocalDateTime): List<WeatherAlert> {
        val startHour = now.truncatedTo(ChronoUnit.HOURS)
        val windowEnd = startHour.plusHours(DETAIL_HOURS)
        // Episodes are detected on everything up to the window end, including the earlier hours of
        // today, so an event that is already under way keeps its real start (and its key), and the
        // black-ice check can see rain that has already stopped.
        val hours = forecast.hourly.filter { !it.time.isAfter(windowEnd) }

        val freezing = freezingRain(hours)
        val hourlyAlerts = buildList {
            addAll(precipitation(hours))
            addAll(thunderstorm(hours))
            addAll(wind(hours))
            addAll(heat(hours))
            addAll(freezing)
            addAll(cold(hours))
            addAll(blackIce(hours).filter { ice -> freezing.none { it.start < ice.end && ice.start < it.end } })
            addAll(fog(hours))
            addAll(uv(hours))
        }.filter { it.end.isAfter(now) && it.start.isBefore(windowEnd) }
        val alerts = hourlyAlerts + temperatureDrop(forecast.daily, now.toLocalDate())

        // Day-level heads-ups are dropped for days the hourly alerts already cover at the same or a
        // higher level; a partial-day hourly alert must not hide a stronger outlook for that day.
        val covered = HashMap<Pair<AlertType, LocalDate>, Severity>()
        alerts.forEach { alert ->
            var day = alert.start.toLocalDate()
            val last = alert.end.minusMinutes(1).toLocalDate()
            while (!day.isAfter(last)) {
                val key = alert.type to day
                val existing = covered[key]
                if (existing == null || alert.severity > existing) covered[key] = alert.severity
                day = day.plusDays(1)
            }
        }
        val outlook = outlook(forecast.daily, windowEnd.toLocalDate(), now.toLocalDate())
            .filter { alert -> covered[alert.type to alert.start.toLocalDate()]?.let { alert.severity > it } ?: true }
        return (alerts + outlook).sortedWith(
            compareBy<WeatherAlert> { it.outlook }
                .thenByDescending { it.severity }
                .thenBy { it.start },
        )
    }

    // region Precipitation -------------------------------------------------------------------

    internal fun isWet(h: HourlyPoint): Boolean {
        val probability = h.precipitationProbability
        return (h.precipitation >= 0.2 && (probability == null || probability >= 25)) ||
            (probability != null && probability >= 55)
    }

    internal fun isSnowy(h: HourlyPoint): Boolean =
        h.snowfall >= 0.1 || h.weatherCode in WeatherCodes.SNOW

    /** Liquid precipitation that freezes on contact: freezing-rain codes or rain at or below 0°C. */
    internal fun isFreezing(h: HourlyPoint): Boolean =
        h.weatherCode in WeatherCodes.FREEZING || (h.liquid >= 0.1 && h.temperature <= 0)

    /** Rain amount of an hour; in mixed hours only the liquid part counts. */
    private fun rainAmount(h: HourlyPoint): Double = if (isSnowy(h)) h.liquid else h.precipitation

    private fun precipitation(hours: List<HourlyPoint>): List<WeatherAlert> {
        val rain = episodes(hours) { isWet(it) && !isFreezing(it) && (!isSnowy(it) || it.liquid >= 0.2) }
            .map(::rainAlert)
        val snow = episodes(hours) { isSnowy(it) && (isWet(it) || it.snowfall >= 0.1) }
            .map(::snowAlert)
        return rain + snow
    }

    private fun rainAlert(hours: List<HourlyPoint>): WeatherAlert {
        val total = hours.sumOf(::rainAmount)
        val peak = hours.maxOf(::rainAmount)
        val probability = hours.mapNotNull { it.precipitationProbability }.maxOrNull()
        val heavyCode = hours.any { it.weatherCode == 65 || it.weatherCode == 82 }
        val severity = when {
            peak >= 25 || total >= 70 -> Severity.RED
            peak >= 12 || total >= 40 -> Severity.ORANGE
            peak >= 5 || total >= 20 || heavyCode -> Severity.YELLOW
            else -> Severity.INFO
        }
        val title = when (severity) {
            Severity.RED -> "Çok şiddetli yağış · Sel riski"
            Severity.ORANGE -> "Şiddetli yağış"
            Severity.YELLOW -> "Kuvvetli yağış"
            Severity.INFO -> if (peak < 0.5 && total < 1) "Hafif yağmur olasılığı" else "Yağmur bekleniyor"
        }
        val detail = buildString {
            if (total >= 0.1) append("Toplam ~${formatAmount(total)} mm")
            if (peak >= 1) append(", saatte en fazla ${formatAmount(peak)} mm")
            if (probability != null) {
                if (isNotEmpty()) append(" · ")
                append("olasılık %$probability")
            }
        }
        val advice = when (severity) {
            Severity.INFO -> listOf(
                "Şemsiye veya yağmurluk yanınıza alın.",
                "Dışarıdaki çamaşırları içeri alın.",
            )
            Severity.YELLOW -> listOf(
                "Şemsiye ve su geçirmez ayakkabı tercih edin.",
                "Yollar kayganlaşabilir; hızınızı düşürüp takip mesafesini artırın.",
                "Çamaşırları ve balkondaki eşyaları içeri alın.",
            )
            Severity.ORANGE -> listOf(
                "Alt geçitlerden, dere yataklarından ve su birikintilerinden uzak durun.",
                "Yolculuk planınızı yağışın yoğun olduğu saatlerin dışına kaydırın.",
                "Gider ve yağmur oluklarının tıkalı olmadığından emin olun.",
                "Bodrum ve zemin kattaki eşyaları su basmasına karşı yükseğe kaldırın.",
            )
            Severity.RED -> listOf(
                "Zorunlu olmadıkça dışarı çıkmayın ve yolculuk yapmayın.",
                "Sel suyuna kesinlikle araçla veya yürüyerek girmeyin.",
                "Bodrum ve zemin katlardaki değerli eşyaları yükseğe taşıyın.",
                "AFAD ve MGM duyurularını takip edin; acil durumda 112'yi arayın.",
            )
        }
        return WeatherAlert(AlertType.RAIN, severity, title, accumulationStart(hours), accumulationEnd(hours), detail, advice)
    }

    private fun snowAlert(hours: List<HourlyPoint>): WeatherAlert {
        val total = hours.sumOf { it.snowfall }
        val severity = when {
            total >= 25 -> Severity.RED
            total >= 10 -> Severity.ORANGE
            total >= 2 -> Severity.YELLOW
            else -> Severity.INFO
        }
        val title = when (severity) {
            Severity.RED -> "Çok yoğun kar yağışı"
            Severity.ORANGE -> "Yoğun kar yağışı"
            Severity.YELLOW -> "Kar yağışı"
            Severity.INFO -> "Hafif kar yağışı"
        }
        val detail = if (total >= 0.1) "Toplam ~${formatAmount(total)} cm kar birikimi bekleniyor" else "Kar yağışı olasılığı"
        val advice = when (severity) {
            Severity.INFO -> listOf(
                "Kaymaz tabanlı ayakkabı giyin.",
                "Kalın ve katmanlı giyinin.",
            )
            Severity.YELLOW -> listOf(
                "Aracınızda kış lastiği ve kar zinciri bulundurun.",
                "Kaldırım ve yollarda kaymaya karşı dikkatli olun.",
                "Toplu taşımada gecikmelere karşı erken yola çıkın.",
            )
            Severity.ORANGE -> listOf(
                "Gerekmedikçe şehirlerarası yolculuk yapmayın.",
                "Araçta battaniye, su, yiyecek ve şarjlı telefon bulundurun.",
                "Çatı saçaklarındaki kar kütlelerine ve buz sarkıtlarına dikkat edin.",
            )
            Severity.RED -> listOf(
                "Zorunlu olmadıkça dışarı çıkmayın.",
                "Elektrik kesintisine karşı el feneri ve powerbank hazırlayın.",
                "Yaşlı ve hasta komşularınızı kontrol edin.",
                "Yol durumu için KGM ve valilik duyurularını takip edin.",
            )
        }
        return WeatherAlert(AlertType.SNOW, severity, title, accumulationStart(hours), accumulationEnd(hours), detail, advice)
    }

    // endregion
    // region Thunderstorm / wind ----------------------------------------------------------

    private fun thunderstorm(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window) { it.weatherCode in WeatherCodes.THUNDER }.map { hours ->
            val hail = hours.any { it.weatherCode in WeatherCodes.HAIL }
            val gusts = hours.maxOf { it.windGusts }
            val severity = when {
                hail && gusts >= 80 -> Severity.RED
                hail -> Severity.ORANGE
                else -> Severity.YELLOW
            }
            val advice = buildList {
                add("Açık alanlarda, ağaç ve direk altında beklemeyin.")
                add("Denizden, göl kıyısından ve yüksek noktalardan uzak durun.")
                add("Hassas elektronik cihazları prizden çekin.")
                if (hail) {
                    add("Aracınızı kapalı otoparka veya korunaklı bir alana çekin.")
                    add("Sera ve tarım ürünleri için koruyucu önlem alın.")
                }
            }
            WeatherAlert(
                type = AlertType.THUNDERSTORM,
                severity = severity,
                title = if (hail) "Dolu ve gök gürültülü fırtına" else "Gök gürültülü sağanak",
                start = hours.first().time,
                end = endOf(hours),
                detail = "Yıldırım ve ani kuvvetli yağış riski · hamleler ${gusts.roundToInt()} km/sa",
                advice = advice,
            )
        }

    private fun wind(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window) { it.windGusts >= 50 || it.windSpeed >= 39 }.map { hours ->
            val gusts = hours.maxOf { it.windGusts }
            val speed = hours.maxOf { it.windSpeed }
            val severity = when {
                gusts >= 90 || speed >= 75 -> Severity.RED
                gusts >= 70 || speed >= 55 -> Severity.ORANGE
                else -> Severity.YELLOW
            }
            val advice = when (severity) {
                Severity.RED -> listOf(
                    "Zorunlu olmadıkça dışarı çıkmayın; pencere ve kapıları kapalı tutun.",
                    "Ağaç, direk, reklam panosu ve çatı altlarından uzak durun.",
                    "Elektrik kesintisine karşı hazırlıklı olun.",
                    "Deniz ve hava ulaşımı seferlerini kontrol edin.",
                )
                Severity.ORANGE -> listOf(
                    "Balkon ve bahçedeki saksı, mobilya gibi eşyaları sabitleyin.",
                    "Ağaç, direk, reklam panosu ve çatı altlarından uzak durun.",
                    "Vapur ve feribot seferlerinde iptal olabilir; seyahatten önce kontrol edin.",
                    "Yüksek araçlarla (kamyonet, karavan) seyahat ederken dikkatli olun.",
                )
                else -> listOf(
                    "Balkon ve bahçedeki hafif eşyaları sabitleyin veya içeri alın.",
                    "Şemsiye kullanımı zorlaşabilir; rüzgarlık tercih edin.",
                )
            }
            WeatherAlert(
                type = AlertType.WIND,
                severity = severity,
                title = when (severity) {
                    Severity.RED -> "Şiddetli fırtına"
                    Severity.ORANGE -> "Fırtına"
                    else -> "Kuvvetli rüzgar"
                },
                start = hours.first().time,
                end = endOf(hours),
                detail = "Rüzgar ${speed.roundToInt()} km/sa, hamleler en fazla ${gusts.roundToInt()} km/sa",
                advice = advice,
            )
        }

    // endregion
    // region Temperature -----------------------------------------------------------------

    private fun heat(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window, maxGap = 2) { it.apparentTemperature >= 32 }.map { hours ->
            val peak = hours.maxOf { it.apparentTemperature }
            val severity = when {
                peak >= 43 -> Severity.RED
                peak >= 39 -> Severity.ORANGE
                peak >= 35 -> Severity.YELLOW
                else -> Severity.INFO
            }
            val advice = buildList {
                add("Bol su için ve yanınızda su bulundurun.")
                add("Açık renkli, ince ve bol kıyafetler tercih edin.")
                if (severity >= Severity.YELLOW) {
                    add("11:00–16:00 arasında doğrudan güneşten kaçının.")
                    add("Spor ve ağır fiziksel işleri serin saatlere kaydırın.")
                }
                if (severity >= Severity.ORANGE) {
                    add("Yaşlıları, çocukları ve kronik hastaları sık sık kontrol edin.")
                    add("Çocukları ve evcil hayvanları asla park halindeki araçta bırakmayın.")
                }
                if (severity == Severity.RED) {
                    add("Baş dönmesi, bulantı, bilinç bulanıklığı sıcak çarpması belirtisidir; 112'yi arayın.")
                }
            }
            WeatherAlert(
                type = AlertType.HEAT,
                severity = severity,
                title = when (severity) {
                    Severity.RED -> "Aşırı sıcak"
                    Severity.ORANGE -> "Çok sıcak hava"
                    Severity.YELLOW -> "Sıcak hava"
                    Severity.INFO -> "Sıcak ve bunaltıcı"
                },
                start = hours.first().time,
                end = endOf(hours),
                detail = "Hissedilen sıcaklık ${peak.roundToInt()}°C'ye kadar çıkacak",
                advice = advice,
            )
        }

    private fun cold(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window, maxGap = 2) { it.temperature <= 2 || it.apparentTemperature <= -10 }.map { hours ->
            val low = hours.minOf { it.temperature }
            val feelsLow = hours.minOf { it.apparentTemperature }
            val severity = when {
                low <= -15 || feelsLow <= -25 -> Severity.RED
                low <= -8 || feelsLow <= -15 -> Severity.ORANGE
                low <= 0 -> Severity.YELLOW
                else -> Severity.INFO
            }
            val advice = when (severity) {
                Severity.INFO -> listOf(
                    "Hassas bitkileri ve fideleri örtün ya da içeri alın.",
                    "Sabah erken saatlerde araç camları buzlanabilir.",
                )
                Severity.YELLOW -> listOf(
                    "Sabah ve gece saatlerinde yol ve kaldırımlarda buzlanmaya dikkat edin.",
                    "Dışarıdaki su tesisatını ve sayaçları dona karşı yalıtın.",
                    "Katmanlı giyinin; bere, eldiven ve atkı kullanın.",
                )
                else -> listOf(
                    "Dışarıda uzun süre kalmaktan kaçının; el ve ayakları sıcak tutun.",
                    "Su tesisatını ve sayaçları dona karşı yalıtın.",
                    "Evcil hayvanları içeride tutun.",
                    "Soba ve ısıtıcı kullanırken karbonmonoksit zehirlenmesine karşı havalandırın.",
                )
            }
            WeatherAlert(
                type = AlertType.COLD,
                severity = severity,
                title = when (severity) {
                    Severity.RED -> "Aşırı soğuk"
                    Severity.ORANGE -> "Sert ayaz ve dondurucu soğuk"
                    Severity.YELLOW -> "Don ve ayaz"
                    Severity.INFO -> "Hafif don riski"
                },
                start = hours.first().time,
                end = endOf(hours),
                detail = "En düşük ${low.roundToInt()}°C, hissedilen ${feelsLow.roundToInt()}°C",
                advice = advice,
            )
        }

    private fun freezingRain(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window, predicate = ::isFreezing)
            .map { hours ->
                val total = hours.sumOf { it.precipitation }
                WeatherAlert(
                    type = AlertType.ICE,
                    severity = if (total >= 5) Severity.RED else Severity.ORANGE,
                    title = "Dondurucu yağmur · Buzlanma",
                    start = accumulationStart(hours),
                    end = accumulationEnd(hours),
                    detail = "Yağmur yere düştüğü anda donabilir; yollar buz tutar",
                    advice = listOf(
                        "Zorunlu değilse araç kullanmayın.",
                        "Kaymaz tabanlı ayakkabı giyin, merdivenlerde tırabzan kullanın.",
                        "Elektrik hatlarında buz yükü nedeniyle kesintiler olabilir.",
                    ),
                )
            }

    /** Wet ground followed by sub-zero temperatures: the classic "gizli buzlanma". */
    private fun blackIce(window: List<HourlyPoint>): List<WeatherAlert> {
        val risky = window.filterIndexed { index, hour ->
            hour.temperature <= 0.5 &&
                window.subList((index - 6).coerceAtLeast(0), index + 1).any { it.liquid >= 0.2 }
        }.map { it.time }.toSet()
        return episodes(window) { it.time in risky }.map { hours ->
            WeatherAlert(
                type = AlertType.ICE,
                severity = Severity.YELLOW,
                title = "Gizli buzlanma riski",
                start = hours.first().time,
                end = endOf(hours),
                detail = "Islak zemin sıfırın altındaki sıcaklıkla donabilir (en düşük ${hours.minOf { it.temperature }.roundToInt()}°C)",
                advice = listOf(
                    "Köprü, viyadük ve gölgeli yollarda özellikle yavaş gidin.",
                    "Ani fren ve direksiyon hareketlerinden kaçının.",
                    "Yürürken kaymaz tabanlı ayakkabı tercih edin.",
                ),
            )
        }
    }

    // endregion
    // region Visibility / UV ----------------------------------------------------------------

    private fun fog(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window) { it.weatherCode in WeatherCodes.FOG || (it.visibility != null && it.visibility < 1000) }
            .map { hours ->
                val minVisibility = hours.mapNotNull { it.visibility }.minOrNull()
                val dense = minVisibility != null && minVisibility < 200
                WeatherAlert(
                    type = AlertType.FOG,
                    severity = if (dense) Severity.ORANGE else Severity.YELLOW,
                    title = if (dense) "Yoğun sis" else "Sis",
                    start = hours.first().time,
                    end = endOf(hours),
                    detail = if (minVisibility != null) "Görüş mesafesi ${formatDistance(minVisibility)}'ye kadar düşebilir" else "Görüş mesafesi azalacak",
                    advice = listOf(
                        "Sis farlarınızı yakın, uzun farları kullanmayın.",
                        "Hızınızı düşürün ve takip mesafesini artırın.",
                        "Uçuş ve feribot seferlerinde gecikme olabilir.",
                    ),
                )
            }

    private fun uv(window: List<HourlyPoint>): List<WeatherAlert> =
        episodes(window) { it.isDay && (it.uvIndex ?: 0.0) >= 6 }.map { hours ->
            val peak = hours.maxOf { it.uvIndex ?: 0.0 }
            val severity = when {
                peak >= 11 -> Severity.ORANGE
                peak >= 8 -> Severity.YELLOW
                else -> Severity.INFO
            }
            WeatherAlert(
                type = AlertType.UV,
                severity = severity,
                title = when (severity) {
                    Severity.ORANGE -> "Aşırı UV ışınımı"
                    Severity.YELLOW -> "Çok yüksek UV"
                    else -> "Yüksek UV"
                },
                start = hours.first().time,
                end = endOf(hours),
                detail = "UV indeksi ${peak.roundToInt()} · korumasız ciltte kısa sürede yanık",
                advice = listOf(
                    "En az SPF 30 güneş kremi sürün, 2 saatte bir yenileyin.",
                    "Şapka ve UV korumalı güneş gözlüğü kullanın.",
                    "${TimeText.hour(hours.first().time)}–${TimeText.hour(endOf(hours))} arası mümkünse gölgede kalın.",
                ),
            )
        }

    // endregion
    // region Daily ----------------------------------------------------------------------

    private fun temperatureDrop(daily: List<DailyPoint>, today: LocalDate): List<WeatherAlert> {
        val days = daily.filter { !it.date.isBefore(today) }.take(3)
        return days.zipWithNext().firstNotNullOfOrNull { (before, after) ->
            val drop = before.temperatureMax - after.temperatureMax
            if (drop < 8) return@firstNotNullOfOrNull null
            WeatherAlert(
                type = AlertType.TEMPERATURE_DROP,
                severity = Severity.INFO,
                title = "Sıcaklık belirgin şekilde düşüyor",
                start = after.date.atStartOfDay(),
                end = after.date.plusDays(1).atStartOfDay(),
                detail = "En yüksek sıcaklık ${before.temperatureMax.roundToInt()}° → ${after.temperatureMax.roundToInt()}° " +
                    "(${drop.roundToInt()}° daha soğuk)",
                advice = listOf(
                    "Yanınıza kalın bir kat alın.",
                    "Ani sıcaklık değişimlerinde soğuk algınlığına karşı dikkatli olun.",
                ),
                allDay = true,
            )
        }?.let(::listOf).orEmpty()
    }

    /** Day-level heads-ups beyond the detailed hourly window, only for notable events. */
    private fun outlook(daily: List<DailyPoint>, fromDate: LocalDate, today: LocalDate): List<WeatherAlert> =
        daily.filter { it.date.isAfter(fromDate.minusDays(1)) && it.date.isBefore(today.plusDays(OUTLOOK_DAYS)) }
            .flatMap { day ->
                val start = day.date.atStartOfDay()
                val end = day.date.plusDays(1).atStartOfDay()
                buildList {
                    when {
                        day.snowfallSum >= 15 -> Severity.ORANGE
                        day.snowfallSum >= 5 -> Severity.YELLOW
                        else -> null
                    }?.let {
                        add(WeatherAlert(AlertType.SNOW, it, "Kar yağışı bekleniyor", start, end,
                            "~${formatAmount(day.snowfallSum)} cm kar birikimi", listOf("Yolculuk planlarınızı kar durumuna göre gözden geçirin."), outlook = true))
                    }
                    when {
                        day.snowfallSum >= 5 -> null
                        day.precipitationSum >= 40 -> Severity.ORANGE
                        day.precipitationSum >= 20 -> Severity.YELLOW
                        else -> null
                    }?.let {
                        add(WeatherAlert(AlertType.RAIN, it, "Kuvvetli yağış bekleniyor", start, end,
                            "Günlük toplam ~${formatAmount(day.precipitationSum)} mm", listOf("Açık hava planlarınızı yağışa göre esnetin."), outlook = true))
                    }
                    if (day.weatherCode in WeatherCodes.THUNDER) {
                        add(WeatherAlert(AlertType.THUNDERSTORM, Severity.YELLOW, "Gök gürültülü sağanak olası", start, end,
                            "Yıldırım ve ani kuvvetli yağış riski", listOf("O gün için açık alan etkinliklerini dikkatle planlayın."), outlook = true))
                    }
                    when {
                        day.windGustsMax >= 90 -> Severity.RED
                        day.windGustsMax >= 70 -> Severity.ORANGE
                        day.windGustsMax >= 55 -> Severity.YELLOW
                        else -> null
                    }?.let {
                        add(WeatherAlert(AlertType.WIND, it, "Kuvvetli rüzgar / fırtına", start, end,
                            "Hamleler ${day.windGustsMax.roundToInt()} km/sa", listOf("Balkon ve bahçedeki eşyaları önceden sabitleyin."), outlook = true))
                    }
                    when {
                        day.apparentMax >= 39 -> Severity.ORANGE
                        day.apparentMax >= 35 -> Severity.YELLOW
                        else -> null
                    }?.let {
                        add(WeatherAlert(AlertType.HEAT, it, "Sıcak hava", start, end,
                            "Hissedilen ${day.apparentMax.roundToInt()}°C", listOf("Su tüketimini artırın, öğle saatlerinde güneşten kaçının."), outlook = true))
                    }
                    when {
                        day.temperatureMin <= -8 -> Severity.ORANGE
                        day.temperatureMin <= -2 -> Severity.YELLOW
                        else -> null
                    }?.let {
                        add(WeatherAlert(AlertType.COLD, it, "Don ve ayaz", start, end,
                            "En düşük ${day.temperatureMin.roundToInt()}°C", listOf("Su tesisatını ve bitkileri dona karşı koruyun."), outlook = true))
                    }
                }
            }

    // endregion
    // region Helpers -------------------------------------------------------------------

    /**
     * Groups consecutive hours matching [predicate]. Up to [maxGap] non-matching hours inside a
     * run are tolerated so a short break does not produce two separate warnings.
     */
    internal fun episodes(
        hours: List<HourlyPoint>,
        maxGap: Int = 1,
        predicate: (HourlyPoint) -> Boolean,
    ): List<List<HourlyPoint>> {
        val result = mutableListOf<List<HourlyPoint>>()
        var current = mutableListOf<HourlyPoint>()
        var gap = 0
        for (hour in hours) {
            if (predicate(hour)) {
                current.add(hour)
                gap = 0
            } else if (current.isNotEmpty()) {
                gap++
                if (gap > maxGap) {
                    result.add(current)
                    current = mutableListOf()
                    gap = 0
                }
            }
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    /** Instant values (temperature, wind, visibility, UV) describe the hour that starts at their timestamp. */
    private fun endOf(hours: List<HourlyPoint>): LocalDateTime = hours.last().time.plusHours(1)

    /** Open-Meteo accumulations (precipitation, rain, snowfall) cover the hour before their timestamp. */
    private fun accumulationStart(hours: List<HourlyPoint>): LocalDateTime = hours.first().time.minusHours(1)

    private fun accumulationEnd(hours: List<HourlyPoint>): LocalDateTime = hours.last().time

    internal fun formatAmount(value: Double): String =
        if (value < 10) ((value * 10).roundToInt() / 10.0).toString().replace('.', ',').removeSuffix(",0")
        else value.roundToInt().toString()

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "${formatAmount(meters / 1000)} km"

    // endregion
}
