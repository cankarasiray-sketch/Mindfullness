package com.mindfullness.weather.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** Turkish date/time phrasing, independent of the device locale. */
object TimeText {
    private val dayNames = mapOf(
        DayOfWeek.MONDAY to "Pazartesi",
        DayOfWeek.TUESDAY to "Salı",
        DayOfWeek.WEDNESDAY to "Çarşamba",
        DayOfWeek.THURSDAY to "Perşembe",
        DayOfWeek.FRIDAY to "Cuma",
        DayOfWeek.SATURDAY to "Cumartesi",
        DayOfWeek.SUNDAY to "Pazar",
    )
    private val shortDayNames = mapOf(
        DayOfWeek.MONDAY to "Pzt",
        DayOfWeek.TUESDAY to "Sal",
        DayOfWeek.WEDNESDAY to "Çar",
        DayOfWeek.THURSDAY to "Per",
        DayOfWeek.FRIDAY to "Cum",
        DayOfWeek.SATURDAY to "Cmt",
        DayOfWeek.SUNDAY to "Paz",
    )
    private val monthNames = listOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık",
    )

    fun hour(time: LocalDateTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

    fun dayName(date: LocalDate): String = dayNames.getValue(date.dayOfWeek)

    fun shortDayName(date: LocalDate): String = shortDayNames.getValue(date.dayOfWeek)

    fun dayMonth(date: LocalDate): String = "${date.dayOfMonth} ${monthNames[date.monthValue - 1]}"

    /** "Bugün", "Yarın" or the weekday name. */
    fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Bugün"
        today.plusDays(1) -> "Yarın"
        else -> dayName(date)
    }

    /** Short label for daily rows: "Bugün", "Yarın", "Cmt", ... */
    fun relativeShortDay(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Bugün"
        today.plusDays(1) -> "Yarın"
        else -> shortDayName(date)
    }

    /**
     * Human readable range for an alert, e.g. "Bugün 15:00–19:00", "Şimdi – 18:00",
     * "Bugün 22:00 – Yarın 04:00". [end] is exclusive (the hour after the last affected hour).
     */
    fun range(start: LocalDateTime, end: LocalDateTime, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val endDay = relativeDay(end.minusMinutes(1).toLocalDate(), today)
        val endDate = end.minusMinutes(1).toLocalDate()
        val startedAlready = !start.isAfter(now)
        return when {
            startedAlready && endDate == today -> "Şimdi – ${hour(end)}"
            startedAlready -> "Şimdi – $endDay ${hour(end)}"
            start.toLocalDate() == endDate ->
                "${relativeDay(start.toLocalDate(), today)} ${hour(start)}–${hour(end)}"
            else -> "${relativeDay(start.toLocalDate(), today)} ${hour(start)} – $endDay ${hour(end)}"
        }
    }

    /** "5 dk önce", "2 sa önce", "1 gün önce". */
    fun ago(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = ((nowMillis - millis) / 60_000.0).roundToInt().coerceAtLeast(0)
        return when {
            minutes < 1 -> "az önce"
            minutes < 60 -> "$minutes dk önce"
            minutes < 60 * 24 -> "${minutes / 60} sa önce"
            else -> "${minutes / (60 * 24)} gün önce"
        }
    }
}
