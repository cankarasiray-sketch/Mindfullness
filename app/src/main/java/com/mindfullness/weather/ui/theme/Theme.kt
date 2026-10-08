package com.mindfullness.weather.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfullness.weather.domain.Condition
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherCodes

object AppColors {
    val Background = Color(0xFF0B1220)
    val Surface = Color(0xFF141D2E)
    val SurfaceHigh = Color(0xFF1C273B)
    val Primary = Color(0xFF8AB4FF)
    val OnSurface = Color(0xFFF2F5FA)
    val OnSurfaceMuted = Color(0xFFB4BFD0)

    /** Translucent "glass" card on top of the weather gradient. */
    val Glass = Color.White.copy(alpha = 0.11f)
    val GlassBorder = Color.White.copy(alpha = 0.16f)
    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.78f)
    val TextTertiary = Color.White.copy(alpha = 0.6f)

    val Rain = Color(0xFF6CC4FF)
    val Sun = Color(0xFFFFC940)
    val Good = Color(0xFF5DD39E)

    fun severity(severity: Severity): Color = when (severity) {
        Severity.INFO -> Color(0xFF7DB8FF)
        Severity.YELLOW -> Color(0xFFFFD43B)
        Severity.ORANGE -> Color(0xFFFF922B)
        Severity.RED -> Color(0xFFFF5A5F)
    }

    /** Text colour that stays readable on top of [severity]. */
    fun onSeverity(severity: Severity): Color = when (severity) {
        Severity.RED -> Color.White
        else -> Color(0xFF1A1300)
    }
}

/** Background gradient matching the current sky. All variants keep white text above 4.5:1. */
fun skyBrush(weatherCode: Int, isDay: Boolean): Brush {
    val colors = when (WeatherCodes.condition(weatherCode)) {
        Condition.CLEAR, Condition.MOSTLY_CLEAR ->
            if (isDay) listOf(Color(0xFF1D5FC2), Color(0xFF173F86), Color(0xFF0F2C63))
            else listOf(Color(0xFF0E1B3D), Color(0xFF0A1430), Color(0xFF050A1C))
        Condition.PARTLY_CLOUDY ->
            if (isDay) listOf(Color(0xFF2B5C9B), Color(0xFF1E416F), Color(0xFF142D50))
            else listOf(Color(0xFF172238), Color(0xFF101828), Color(0xFF080D18))
        Condition.OVERCAST, Condition.FOG ->
            if (isDay) listOf(Color(0xFF4A5A6E), Color(0xFF33404F), Color(0xFF222B36))
            else listOf(Color(0xFF1E2530), Color(0xFF151A22), Color(0xFF0B0E13))
        Condition.DRIZZLE, Condition.RAIN, Condition.SHOWERS, Condition.HEAVY_RAIN, Condition.FREEZING_RAIN ->
            if (isDay) listOf(Color(0xFF34495E), Color(0xFF263646), Color(0xFF18232E))
            else listOf(Color(0xFF17212C), Color(0xFF10171F), Color(0xFF090D12))
        Condition.SNOW, Condition.SLEET ->
            if (isDay) listOf(Color(0xFF4B6886), Color(0xFF354C64), Color(0xFF233446))
            else listOf(Color(0xFF1C2738), Color(0xFF131C29), Color(0xFF0A1019))
        Condition.THUNDERSTORM, Condition.HAIL ->
            listOf(Color(0xFF2E2547), Color(0xFF1F1932), Color(0xFF110D1D))
    }
    return Brush.verticalGradient(colors)
}

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Thin, letterSpacing = (-2).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun HavaUyariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Primary,
            onPrimary = Color(0xFF0A1A33),
            primaryContainer = Color(0xFF23406E),
            onPrimaryContainer = Color(0xFFDCE6FF),
            secondaryContainer = AppColors.SurfaceHigh,
            onSecondaryContainer = AppColors.OnSurface,
            background = AppColors.Background,
            onBackground = AppColors.OnSurface,
            surface = AppColors.Surface,
            onSurface = AppColors.OnSurface,
            surfaceVariant = AppColors.SurfaceHigh,
            onSurfaceVariant = AppColors.OnSurfaceMuted,
            surfaceContainer = AppColors.Surface,
            surfaceContainerHigh = AppColors.SurfaceHigh,
            surfaceContainerHighest = Color(0xFF243149),
            outline = Color(0xFF3A4760),
            outlineVariant = Color(0xFF2A3549),
        ),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
