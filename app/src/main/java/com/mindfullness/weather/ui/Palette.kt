package com.mindfullness.weather.ui

import android.graphics.Color
import com.mindfullness.weather.domain.Condition
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherCodes

object Palette {
    val BACKGROUND = Color.parseColor("#0B1220")
    val SURFACE = Color.parseColor("#141D2E")
    val SURFACE_HIGH = Color.parseColor("#1C273B")
    val OUTLINE = Color.parseColor("#2A3549")
    val ACCENT = Color.parseColor("#8AB4FF")
    val ON_SURFACE = Color.parseColor("#F2F5FA")
    val ON_SURFACE_MUTED = Color.parseColor("#B4BFD0")

    /** Text and translucent "glass" surfaces on top of the sky gradient. */
    val TEXT = Color.WHITE
    val TEXT_SECONDARY = Color.argb(199, 255, 255, 255)
    val TEXT_TERTIARY = Color.argb(214, 255, 255, 255)
    val GLASS = Color.argb(28, 255, 255, 255)
    val GLASS_STRONG = Color.argb(38, 255, 255, 255)
    val GLASS_BORDER = Color.argb(41, 255, 255, 255)
    val DIVIDER = Color.argb(20, 255, 255, 255)

    val RAIN = Color.parseColor("#6CC4FF")

    /** Lighter rain tint for small text, readable on the glass cards. */
    val RAIN_TEXT = Color.parseColor("#B3E0FF")
    val SUN = Color.parseColor("#FFC940")
    val GOOD = Color.parseColor("#5DD39E")
    val WARNING_SURFACE = Color.argb(51, 255, 146, 43)

    fun severity(severity: Severity): Int = when (severity) {
        Severity.INFO -> Color.parseColor("#7DB8FF")
        Severity.YELLOW -> Color.parseColor("#FFD43B")
        Severity.ORANGE -> Color.parseColor("#FF922B")
        Severity.RED -> Color.parseColor("#FF5A5F")
    }

    /** Dark text that stays readable on every severity colour (at least 6:1, red included). */
    val ON_SEVERITY = Color.parseColor("#1A1300")

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Background gradient matching the sky; opaque white text stays above 4.5:1 on every variant. */
    fun sky(weatherCode: Int?, isDay: Boolean): IntArray {
        val hex = when (weatherCode?.let(WeatherCodes::condition)) {
            null -> listOf("#1D3B6A", "#0F1F3D", "#070F21")
            Condition.CLEAR, Condition.MOSTLY_CLEAR ->
                if (isDay) listOf("#1D5FC2", "#173F86", "#0F2C63") else listOf("#0E1B3D", "#0A1430", "#050A1C")
            Condition.PARTLY_CLOUDY ->
                if (isDay) listOf("#2B5C9B", "#1E416F", "#142D50") else listOf("#172238", "#101828", "#080D18")
            Condition.OVERCAST, Condition.FOG ->
                if (isDay) listOf("#4A5A6E", "#33404F", "#222B36") else listOf("#1E2530", "#151A22", "#0B0E13")
            Condition.DRIZZLE, Condition.RAIN, Condition.SHOWERS, Condition.HEAVY_RAIN, Condition.FREEZING_RAIN ->
                if (isDay) listOf("#34495E", "#263646", "#18232E") else listOf("#17212C", "#10171F", "#090D12")
            Condition.SNOW, Condition.SLEET ->
                if (isDay) listOf("#4B6886", "#354C64", "#233446") else listOf("#1C2738", "#131C29", "#0A1019")
            Condition.THUNDERSTORM, Condition.HAIL -> listOf("#2E2547", "#1F1932", "#110D1D")
        }
        return hex.map(Color::parseColor).toIntArray()
    }
}
