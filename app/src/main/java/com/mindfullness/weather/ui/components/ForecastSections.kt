package com.mindfullness.weather.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.CurrentWeather
import com.mindfullness.weather.domain.DailyPoint
import com.mindfullness.weather.domain.HourlyPoint
import com.mindfullness.weather.domain.PrecipitationSummary
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.TimeText
import com.mindfullness.weather.domain.Tip
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.domain.WeatherCodes
import com.mindfullness.weather.ui.theme.AppColors
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

fun Double.deg(): String = "${roundToInt()}°"

// region Hero ---------------------------------------------------------------------------------

@Composable
fun CurrentConditions(current: CurrentWeather, today: DailyPoint?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                current.temperature.deg(),
                style = MaterialTheme.typography.displayLarge,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            WeatherIcon(current.weatherCode, current.isDay, size = 120.dp)
        }
        Text(
            WeatherCodes.describe(current.weatherCode),
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        val range = today?.let { "En yüksek ${it.temperatureMax.deg()} · En düşük ${it.temperatureMin.deg()}" }
        Text(
            listOfNotNull(range, "Hissedilen ${current.apparentTemperature.deg()}").joinToString("  ·  "),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
        )
    }
}

// endregion
// region Tips -------------------------------------------------------------------------------

@Composable
fun TipsRow(tips: List<Tip>, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle(Icons.Rounded.WorkOutline, "Yanınıza alın", Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tips.forEach { tip ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AppColors.Glass)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tipIcon(tip.kind), contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tip.text, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
                }
            }
        }
    }
}

// endregion
// region Precipitation chart ------------------------------------------------------------------

@Composable
fun PrecipitationCard(summary: PrecipitationSummary, hours: List<HourlyPoint>, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        SectionTitle(Icons.Rounded.Umbrella, "Yağış · 24 saat")
        Spacer(Modifier.height(10.dp))
        Text(summary.headline, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        summary.detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        }
        if (hours.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            PrecipitationChart(hours)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                hours.forEachIndexed { index, hour ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (index % 4 == 0) {
                            Text(
                                if (index == 0) "Şimdi" else hour.time.hour.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(AppColors.Rain))
                Spacer(Modifier.width(6.dp))
                Text("Miktar (mm)", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.width(14.dp).height(2.dp).background(Color.White.copy(alpha = 0.7f)))
                Spacer(Modifier.width(6.dp))
                Text("Olasılık (%)", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun PrecipitationChart(hours: List<HourlyPoint>) {
    val maxAmount = max(2.0, hours.maxOf { it.precipitation })
    Canvas(Modifier.fillMaxWidth().height(92.dp)) {
        val slot = size.width / hours.size
        val barWidth = slot * 0.62f
        val chartHeight = size.height

        // Baseline grid at 50% / 100% probability.
        listOf(0f, 0.5f).forEach { fraction ->
            drawLine(
                Color.White.copy(alpha = 0.08f),
                Offset(0f, chartHeight * fraction),
                Offset(size.width, chartHeight * fraction),
                strokeWidth = 1.dp.toPx(),
            )
        }
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, chartHeight), Offset(size.width, chartHeight), 1.dp.toPx())

        hours.forEachIndexed { index, hour ->
            if (hour.precipitation <= 0.0) return@forEachIndexed
            val height = (hour.precipitation / maxAmount).toFloat().coerceIn(0.04f, 1f) * chartHeight
            val color = if (AlertEngine.isSnowy(hour)) Color.White else AppColors.Rain
            drawRoundRect(
                color = color,
                topLeft = Offset(index * slot + (slot - barWidth) / 2, chartHeight - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 3),
            )
        }

        val probabilities = hours.map { it.precipitationProbability }
        if (probabilities.any { it != null }) {
            val path = Path()
            probabilities.forEachIndexed { index, probability ->
                val x = index * slot + slot / 2
                val y = chartHeight * (1f - (probability ?: 0) / 100f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

// endregion
// region Hourly -----------------------------------------------------------------------------

@Composable
fun HourlyCard(hours: List<HourlyPoint>, modifier: Modifier = Modifier) {
    GlassCard(modifier, contentPadding = PaddingValues(vertical = 16.dp)) {
        SectionTitle(Icons.Rounded.Schedule, "Saatlik tahmin", Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(hours.size) { index ->
                HourItem(hours[index], isNow = index == 0)
            }
        }
    }
}

@Composable
private fun HourItem(hour: HourlyPoint, isNow: Boolean) {
    Column(
        Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isNow) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (isNow) "Şimdi" else TimeText.hour(hour.time),
            style = MaterialTheme.typography.labelMedium,
            color = if (isNow) AppColors.TextPrimary else AppColors.TextSecondary,
            fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        WeatherIcon(hour.weatherCode, hour.isDay, size = 34.dp)
        Spacer(Modifier.height(6.dp))
        Text(hour.temperature.deg(), style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        val probability = hour.precipitationProbability ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Opacity,
                contentDescription = null,
                tint = if (probability >= 20) AppColors.Rain else Color.Transparent,
                modifier = Modifier.size(11.dp),
            )
            Text(
                if (probability >= 20) "%$probability" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.Rain,
            )
        }
    }
}

// endregion
// region Daily --------------------------------------------------------------------------------

@Composable
fun DailyCard(
    days: List<DailyPoint>,
    today: LocalDate,
    alertDays: Map<LocalDate, Severity>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val weekMin = days.minOf { it.temperatureMin }
    val weekMax = days.maxOf { it.temperatureMax }
    GlassCard(modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        SectionTitle(Icons.Rounded.CalendarMonth, "${days.size} günlük tahmin")
        Spacer(Modifier.height(6.dp))
        days.forEachIndexed { index, day ->
            if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            DayRow(day, today, weekMin, weekMax, alertDays[day.date])
        }
    }
}

@Composable
private fun DayRow(day: DailyPoint, today: LocalDate, weekMin: Double, weekMax: Double, severity: Severity?) {
    var expanded by rememberSaveable(day.date.toString()) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TimeText.relativeShortDay(day.date, today),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                if (severity != null && severity >= Severity.YELLOW) {
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(AppColors.severity(severity)))
                }
            }
            WeatherIcon(day.weatherCode, isDay = true, size = 30.dp)
            Box(Modifier.width(46.dp), contentAlignment = Alignment.Center) {
                val probability = day.precipitationProbabilityMax ?: 0
                if (probability >= 20) {
                    Text("%$probability", style = MaterialTheme.typography.labelMedium, color = AppColors.Rain)
                }
            }
            Text(
                day.temperatureMin.deg(),
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(36.dp),
            )
            TemperatureRangeBar(day.temperatureMin, day.temperatureMax, weekMin, weekMax, Modifier.weight(1f).padding(horizontal = 10.dp))
            Text(
                day.temperatureMax.deg(),
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary,
                modifier = Modifier.width(36.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${TimeText.dayName(day.date)}, ${TimeText.dayMonth(day.date)} · ${WeatherCodes.describe(day.weatherCode)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextPrimary,
                )
                DetailLine("Yağış", "${AlertEngine.formatAmount(day.precipitationSum)} mm" +
                    (day.precipitationProbabilityMax?.let { " · olasılık %$it" } ?: ""))
                if (day.snowfallSum > 0) DetailLine("Kar", "${AlertEngine.formatAmount(day.snowfallSum)} cm")
                DetailLine("Rüzgar", "${day.windSpeedMax.roundToInt()} km/sa · hamle ${day.windGustsMax.roundToInt()} km/sa")
                DetailLine("Hissedilen", "${day.apparentMin.deg()} / ${day.apparentMax.deg()}")
                day.uvIndexMax?.let { DetailLine("UV indeksi", "${it.roundToInt()} · ${uvLabel(it)}") }
                if (day.sunrise != null && day.sunset != null) {
                    DetailLine("Gün doğumu / batımı", "${TimeText.hour(day.sunrise)} / ${TimeText.hour(day.sunset)}")
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
    }
}

private val ColdColor = Color(0xFF6CC4FF)
private val MildColor = Color(0xFF8BE0A4)
private val WarmColor = Color(0xFFFFC940)
private val HotColor = Color(0xFFFF7A45)

private fun temperatureColor(t: Double): Color = when {
    t <= 0 -> ColdColor
    t <= 15 -> lerp(ColdColor, MildColor, (t / 15).toFloat())
    t <= 25 -> lerp(MildColor, WarmColor, ((t - 15) / 10).toFloat())
    t <= 35 -> lerp(WarmColor, HotColor, ((t - 25) / 10).toFloat())
    else -> HotColor
}

@Composable
private fun TemperatureRangeBar(min: Double, max: Double, weekMin: Double, weekMax: Double, modifier: Modifier) {
    val span = (weekMax - weekMin).coerceAtLeast(1.0)
    Canvas(modifier.height(6.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = radius)
        val start = ((min - weekMin) / span).toFloat() * size.width
        val end = ((max - weekMin) / span).toFloat() * size.width
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(temperatureColor(min), temperatureColor(max)), startX = start, endX = end),
            topLeft = Offset(start, 0f),
            size = Size((end - start).coerceAtLeast(size.height), size.height),
            cornerRadius = radius,
        )
    }
}

// endregion

fun uvLabel(uv: Double): String = when {
    uv < 3 -> "Düşük"
    uv < 6 -> "Orta"
    uv < 8 -> "Yüksek"
    uv < 11 -> "Çok yüksek"
    else -> "Aşırı"
}

/** Severity of the most serious alert per day, for the dots in the daily list. */
fun alertDays(alerts: List<WeatherAlert>): Map<LocalDate, Severity> {
    val result = mutableMapOf<LocalDate, Severity>()
    alerts.forEach { alert ->
        var date = alert.start.toLocalDate()
        val last = alert.end.minusMinutes(1).toLocalDate()
        while (!date.isAfter(last)) {
            val existing = result[date]
            if (existing == null || alert.severity > existing) result[date] = alert.severity
            date = date.plusDays(1)
        }
    }
    return result
}
