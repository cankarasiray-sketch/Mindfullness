package com.mindfullness.weather.domain

import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Pollen of one type on one day: the daily mean decides the level, the peak hour helps planning. */
data class PollenDay(
    val date: LocalDate,
    val mean: Double,
    val peak: Double,
    val peakTime: LocalDateTime,
    val level: PollenLevel,
    /** False once today's peak has passed and the rest of the day stays well below it. */
    val peakAhead: Boolean = true,
)

data class PollenOutlook(val type: PollenType, val days: List<PollenDay>) {
    fun on(date: LocalDate): PollenDay? = days.firstOrNull { it.date == date }
}

/** What the allergy card shows. */
data class AllergySummary(
    val headline: String,
    val detail: String?,
    /** Types with data, the user's allergens first, then by today's level. */
    val types: List<PollenOutlook>,
    val dates: List<LocalDate>,
    val hasData: Boolean,
    /** Highest level among the relevant types today (the allergens, or all without a profile). */
    val todayLevel: PollenLevel,
)

object Allergy {
    const val DAYS = 3L
    private val TR = Locale.forLanguageTag("tr-TR")

    /**
     * Daily pollen of [type] on [date]; null without data for that day. The peak is looked for
     * from [now] on, so today's advice points at the hours still ahead.
     */
    fun day(air: List<AirPoint>, type: PollenType, date: LocalDate, now: LocalDateTime? = null): PollenDay? {
        val points = air.filter { it.time.toLocalDate() == date }.mapNotNull { point -> point.pollen[type]?.let { point.time to it } }
        // A handful of hours is not a day: a mean of the late evening alone would mislead.
        if (points.size < 6) return null
        val mean = points.sumOf { it.second } / points.size
        val hour = now?.truncatedTo(ChronoUnit.HOURS)
        val ahead = points.filter { hour == null || !it.first.isBefore(hour) }
        val dayPeak = points.maxOf { it.second }
        val peak = (ahead.ifEmpty { points }).maxBy { it.second }
        // Worth pointing at only while the hours ahead still come close to the day's peak.
        val peakAhead = ahead.isNotEmpty() && peak.second >= dayPeak * 0.75
        return PollenDay(date, mean, peak.second, peak.first, type.level(mean), peakAhead)
    }

    fun outlook(air: List<AirPoint>, today: LocalDate, now: LocalDateTime? = null): List<PollenOutlook> =
        PollenType.values().mapNotNull { type ->
            val days = (0 until DAYS).mapNotNull { day(air, type, today.plusDays(it), now) }
            if (days.isEmpty()) null else PollenOutlook(type, days)
        }

    fun summary(forecast: Forecast, now: LocalDateTime, profile: AllergyProfile?): AllergySummary {
        val today = now.toLocalDate()
        val dates = (0 until DAYS).map { today.plusDays(it) }
        val all = outlook(forecast.air, today, now)
        val types = all.sortedWith(
            compareByDescending<PollenOutlook> { profile?.types?.contains(it.type) == true }
                .thenByDescending { it.on(today)?.level ?: PollenLevel.NONE }
                .thenBy { it.type.ordinal },
        )
        val measured = measuredToday(forecast, today, profile)
        if (all.isEmpty() && forecast.bulletin?.isCurrent(today) != true) {
            return AllergySummary(
                // No value at all: out of season, or outside Europe; either way nothing to show yet.
                headline = "Şu an polen tahmini yok",
                detail = "Polen tahmini Avrupa ve Türkiye için, polen mevsiminde yapılır.",
                types = emptyList(), dates = dates, hasData = false, todayLevel = PollenLevel.NONE,
            )
        }
        val relevant = all.filter { profile == null || it.type in profile.types }
        val todays = relevant.mapNotNull { outlook -> outlook.on(today)?.let { outlook.type to it } }
        val worst = todays.maxByOrNull { it.second.level }
        val forecastLevel = worst?.second?.level ?: PollenLevel.NONE
        val local = measured.maxByOrNull { it.level }
        // The city's measured counts speak for themselves where they report more than the model.
        if (local != null && local.level >= PollenLevel.MODERATE && local.level > forecastLevel) {
            // The card lists the bulletin's dominant pollen right below, so the source is enough here.
            return AllergySummary(
                headline = "Bugün ${local.subject} ${local.level.label.lowercase(TR)}",
                detail = "Kaynak: ${forecast.bulletin!!.bulletin.name}",
                types = types, dates = dates, hasData = true, todayLevel = local.level,
            )
        }
        val todayLevel = maxOf(forecastLevel, local?.level ?: PollenLevel.NONE)
        val headline = when {
            // Data for other types, but none for the allergens: out of their season.
            worst == null && local == null && profile != null -> "Alerjiniz olan polenler şu an görülmüyor"
            worst == null && local == null -> "Bugün için polen verisi yok"
            todayLevel <= PollenLevel.LOW || worst == null ->
                if (profile != null) "Alerjiniz olan polenler bugün düşük" else "Bugün polen düşük"
            else -> "Bugün ${worst.first.shortLabel.lowercase(TR)} poleni ${todayLevel.label.lowercase(TR)}"
        }
        val detail = buildList {
            if (worst != null && todayLevel >= PollenLevel.MODERATE && worst.second.peakAhead) {
                add("En yoğun ${TimeText.hour(worst.second.peakTime)} civarı")
            }
            // A rise tomorrow is worth knowing today.
            val tomorrow = today.plusDays(1)
            relevant.mapNotNull { outlook -> outlook.on(tomorrow)?.let { outlook.type to it } }
                .maxByOrNull { it.second.level }
                ?.takeIf { it.second.level >= PollenLevel.MODERATE && it.second.level > todayLevel }
                ?.let { (type, day) -> add("yarın ${type.shortLabel.lowercase(TR)} ${day.level.label.lowercase(TR)}") }
        }.joinToString(" · ").replaceFirstChar { it.titlecase(TR) }.ifBlank { null }
        return AllergySummary(headline, detail, types, dates, hasData = true, todayLevel = todayLevel)
    }

    /** A group of the city bulletin that concerns the user, with what to call it in a sentence. */
    internal class Measured(val group: BulletinGroup, val level: PollenLevel, val subject: String, val types: List<PollenType>)

    /**
     * Today's groups of a current city bulletin that concern the user: without a profile all of
     * them, with one those naming one of the allergens among their dominant pollen.
     */
    internal fun measuredToday(forecast: Forecast, today: LocalDate, profile: AllergyProfile?): List<Measured> =
        measuredOn(forecast.bulletin, today, today, profile)

    private fun measuredOn(reading: BulletinReading?, date: LocalDate, today: LocalDate, profile: AllergyProfile?): List<Measured> {
        if (reading == null || !reading.isCurrent(today)) return emptyList()
        return reading.groups.mapNotNull { group ->
            // A weekly count describes the days around it, so it stands for today only.
            val level = (if (reading.isWeekly) group.weekLevel.takeIf { date == today } else group.on(date)) ?: return@mapNotNull null
            if (profile == null) return@mapNotNull Measured(group, level, "${group.group.label.lowercase(TR)} polenleri", emptyList())
            val types = profile.types.filter { group.names(it) }.sortedBy { it.ordinal }
            if (types.isEmpty()) null else Measured(group, level, "${types.first().shortLabel.lowercase(TR)} poleni", types)
        }
    }

    /** The user's allergen with the most pollen today, from the forecast or the city bulletin. */
    internal fun worstToday(forecast: Forecast, now: LocalDateTime, profile: AllergyProfile): Pair<String, PollenLevel>? {
        val forecastWorst = profile.types.mapNotNull { type -> day(forecast.air, type, now.toLocalDate(), now)?.let { type to it.level } }
            .maxByOrNull { it.second }
            ?.let { (type, level) -> "${type.shortLabel.lowercase(TR)} poleni" to level }
        val measured = measuredToday(forecast, now.toLocalDate(), profile).maxByOrNull { it.level }?.let { it.subject to it.level }
        return listOfNotNull(forecastWorst, measured).maxByOrNull { it.second }
    }

    /**
     * Thunderstorms in the grass pollen season can set off severe asthma attacks, even in people
     * with hay fever alone ("thunderstorm asthma"): the gust front sweeps pollen up, moisture bursts
     * it into fine fragments and the downdraft brings them down to where people breathe.
     */
    internal fun withStormAdvice(alert: WeatherAlert, air: List<AirPoint>, profile: AllergyProfile): WeatherAlert {
        if (PollenType.GRASS !in profile.types) return alert
        val level = day(air, PollenType.GRASS, alert.start.toLocalDate())?.level ?: return alert
        if (level < PollenLevel.MODERATE) return alert
        val advice = "Çim poleni ${level.label.lowercase(TR)}: fırtına öncesi rüzgârda ve fırtına sırasında dışarıda " +
            "kalmayın, pencereleri kapatın. Fırtına, polen alerjisi olanlarda astım atağını tetikleyebilir."
        return alert.copy(advice = alert.advice + advice)
    }

    /** Warnings for the days the user's allergens reach a high level, in the forecast or the city bulletin. */
    internal fun alerts(air: List<AirPoint>, now: LocalDateTime, profile: AllergyProfile, bulletin: BulletinReading? = null): List<WeatherAlert> =
        mergeDays(dailyAlerts(air, now, profile, bulletin))

    /** Consecutive days with the same warning read as one ("Bugün – Cmt") rather than as a card each. */
    private fun mergeDays(alerts: List<WeatherAlert>): List<WeatherAlert> {
        val merged = ArrayList<WeatherAlert>()
        for (alert in alerts) {
            val last = merged.lastOrNull()
            if (last != null && last.end == alert.start && last.title == alert.title && last.severity == alert.severity) {
                merged[merged.size - 1] = last.copy(end = alert.end)
            } else {
                merged += alert
            }
        }
        return merged
    }

    private fun dailyAlerts(air: List<AirPoint>, now: LocalDateTime, profile: AllergyProfile, bulletin: BulletinReading?): List<WeatherAlert> =
        (0 until DAYS).mapNotNull { offset ->
            val date = now.toLocalDate().plusDays(offset)
            val high = profile.types.mapNotNull { type -> day(air, type, date, now)?.let { type to it } }
                .filter { it.second.level >= PollenLevel.HIGH }
                .sortedByDescending { it.second.mean / it.first.group.limits.second }
            val measured = measuredOn(bulletin, date, now.toLocalDate(), profile).filter { it.level >= PollenLevel.HIGH }
            if (high.isEmpty() && measured.isEmpty()) return@mapNotNull null
            val veryHigh = high.any { it.second.level == PollenLevel.VERY_HIGH } || measured.any { it.level == PollenLevel.VERY_HIGH }
            val names = (high.map { it.first } + measured.flatMap { it.types }).distinct()
                .joinToString(", ") { it.shortLabel.lowercase(TR) }
            val peak = high.firstOrNull()?.second
            val details = high.map { (type, day) ->
                "${type.shortLabel} ${day.level.label.lowercase(TR)} (~${day.mean.roundToInt()} tane/m³)"
            } + measured.map { "${bulletin!!.bulletin.shortName}: ${it.group.group.label.lowercase(TR)} ${it.level.label.lowercase(TR)}" }
            WeatherAlert(
                type = AlertType.POLLEN,
                severity = if (veryHigh) Severity.ORANGE else Severity.YELLOW,
                title = (if (veryHigh) "Çok yüksek polen · " else "Yüksek polen · ") + names,
                start = date.atStartOfDay(),
                end = date.plusDays(1).atStartOfDay(),
                detail = details.joinToString(" · ") +
                    (if (peak != null && peak.peakAhead) " · en yoğun ${TimeText.hour(peak.peakTime)} civarı" else ""),
                advice = buildList {
                    add("Alerji ilacınızı doktorunuzun önerdiği şekilde, belirtiler başlamadan alın.")
                    add("Pencereleri özellikle öğle ve öğleden sonra saatlerinde kapalı tutun; araçta havalandırmayı iç devreye alın.")
                    add("Dışarıdan dönünce yüzünüzü yıkayın, mümkünse duş alıp kıyafetlerinizi değiştirin.")
                    add("Güneş gözlüğü ve şapka polenin göze ve saçlara yerleşmesini azaltır.")
                    add("Çamaşırları dışarıda kurutmayın.")
                    if (veryHigh) add("Astımınız varsa kurtarıcı ilacınızı yanınızda bulundurun; dış mekân sporunu erteleyin.")
                },
                // The day after tomorrow is beyond the detailed window.
                outlook = offset >= 2,
                allDay = true,
            )
        }
}
