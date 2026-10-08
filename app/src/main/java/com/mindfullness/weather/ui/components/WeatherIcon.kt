package com.mindfullness.weather.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.Condition
import com.mindfullness.weather.domain.WeatherCodes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colourful, resolution independent weather illustration drawn on a canvas. */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Canvas(modifier.size(size)) { drawWeather(code, isDay) }
}

private val SunCore = listOf(Color(0xFFFFE680), Color(0xFFFFB300))
private val SunRay = Color(0xFFFFC233)
private val MoonColor = Color(0xFFF3E6BD)
private val CloudLight = listOf(Color(0xFFFFFFFF), Color(0xFFDCE4EE))
private val CloudGrey = listOf(Color(0xFFD4DCE6), Color(0xFFA9B5C4))
private val CloudDark = listOf(Color(0xFF9AA6B8), Color(0xFF6B788C))
private val RainColor = Color(0xFF6CC4FF)
private val BoltColor = Color(0xFFFFD43B)

fun DrawScope.drawWeather(code: Int, isDay: Boolean) {
    val s = size.minDimension
    when (WeatherCodes.condition(code)) {
        Condition.CLEAR -> if (isDay) sun(Offset(.5f * s, .5f * s), .2f * s) else {
            moon(Offset(.5f * s, .5f * s), .27f * s)
            sparkle(Offset(.8f * s, .22f * s), .06f * s)
            sparkle(Offset(.2f * s, .78f * s), .045f * s)
        }
        Condition.MOSTLY_CLEAR -> {
            celestial(isDay, Offset(.42f * s, .4f * s), .19f * s)
            cloud(.40f * s, .52f * s, .54f * s, CloudLight)
        }
        Condition.PARTLY_CLOUDY -> {
            celestial(isDay, Offset(.36f * s, .34f * s), .16f * s)
            cloud(.18f * s, .36f * s, .76f * s, CloudLight)
        }
        Condition.OVERCAST -> {
            cloud(.06f * s, .16f * s, .62f * s, CloudGrey)
            cloud(.24f * s, .34f * s, .70f * s, CloudLight)
        }
        Condition.FOG -> {
            cloud(.16f * s, .06f * s, .68f * s, CloudGrey)
            fogLines(s)
        }
        Condition.DRIZZLE -> {
            cloud(.1f * s, .06f * s, .8f * s, CloudLight)
            drizzle(s)
        }
        Condition.RAIN -> {
            cloud(.1f * s, .06f * s, .8f * s, CloudGrey)
            rain(s, drops = 3)
        }
        Condition.HEAVY_RAIN -> {
            cloud(.1f * s, .06f * s, .8f * s, CloudDark)
            rain(s, drops = 4)
        }
        Condition.SHOWERS -> {
            if (isDay) celestial(true, Offset(.66f * s, .26f * s), .15f * s)
            cloud(.08f * s, .1f * s, .74f * s, CloudGrey)
            rain(s, drops = 3)
        }
        Condition.FREEZING_RAIN, Condition.SLEET -> {
            cloud(.1f * s, .06f * s, .8f * s, CloudGrey)
            rainDrop(Offset(.34f * s, .66f * s), s)
            snowflake(Offset(.52f * s, .78f * s), .07f * s)
            rainDrop(Offset(.70f * s, .66f * s), s)
        }
        Condition.SNOW -> {
            cloud(.1f * s, .06f * s, .8f * s, CloudLight)
            snowflake(Offset(.30f * s, .72f * s), .075f * s)
            snowflake(Offset(.52f * s, .86f * s), .075f * s)
            snowflake(Offset(.72f * s, .70f * s), .075f * s)
        }
        Condition.THUNDERSTORM -> {
            cloud(.1f * s, .04f * s, .8f * s, CloudDark)
            bolt(s)
            rainDrop(Offset(.30f * s, .68f * s), s)
            rainDrop(Offset(.78f * s, .68f * s), s)
        }
        Condition.HAIL -> {
            cloud(.1f * s, .04f * s, .8f * s, CloudDark)
            bolt(s)
            drawCircle(Color(0xFFE3F2FF), .04f * s, Offset(.28f * s, .74f * s))
            drawCircle(Color(0xFFE3F2FF), .04f * s, Offset(.80f * s, .72f * s))
            drawCircle(Color(0xFFE3F2FF), .035f * s, Offset(.30f * s, .90f * s))
        }
    }
}

private fun DrawScope.celestial(isDay: Boolean, center: Offset, radius: Float) {
    if (isDay) sun(center, radius) else moon(center, radius * 1.25f)
}

private fun DrawScope.sun(center: Offset, radius: Float) {
    for (i in 0 until 8) {
        val angle = (i * PI / 4).toFloat()
        val dir = Offset(cos(angle), sin(angle))
        drawLine(
            color = SunRay,
            start = center + dir * (radius * 1.42f),
            end = center + dir * (radius * 1.85f),
            strokeWidth = radius * 0.26f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(Brush.radialGradient(SunCore, center, radius), radius, center)
}

private fun DrawScope.moon(center: Offset, radius: Float) {
    val outer = Path().apply { addOval(Rect(center, radius)) }
    val cut = Path().apply { addOval(Rect(center + Offset(radius * 0.55f, -radius * 0.4f), radius * 0.85f)) }
    val crescent = Path().apply { op(outer, cut, PathOperation.Difference) }
    drawPath(crescent, MoonColor)
}

private fun DrawScope.sparkle(center: Offset, radius: Float) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, MoonColor)
}

/** Cloud whose bounding box starts at ([left], [top]) and is [width] wide, ~0.62 * width tall. */
private fun DrawScope.cloud(left: Float, top: Float, width: Float, colors: List<Color>) {
    val brush = Brush.verticalGradient(colors, startY = top, endY = top + width * 0.62f)
    drawRoundRect(
        brush = brush,
        topLeft = Offset(left + width * 0.05f, top + width * 0.35f),
        size = Size(width * 0.9f, width * 0.27f),
        cornerRadius = CornerRadius(width * 0.135f),
    )
    drawCircle(brush, width * 0.28f, Offset(left + width * 0.56f, top + width * 0.31f))
    drawCircle(brush, width * 0.19f, Offset(left + width * 0.29f, top + width * 0.41f))
}

private fun DrawScope.rainDrop(top: Offset, s: Float) {
    drawLine(RainColor, top, top + Offset(-.05f * s, .15f * s), strokeWidth = .055f * s, cap = StrokeCap.Round)
}

private fun DrawScope.rain(s: Float, drops: Int) {
    val xs = if (drops == 4) listOf(.26f, .44f, .62f, .80f) else listOf(.32f, .52f, .72f)
    xs.forEachIndexed { index, x ->
        val y = if (index % 2 == 0) .64f else .74f
        rainDrop(Offset(x * s, y * s), s)
    }
}

private fun DrawScope.drizzle(s: Float) {
    listOf(.32f to .68f, .52f to .76f, .72f to .68f, .42f to .88f, .62f to .88f).forEach { (x, y) ->
        drawCircle(RainColor, .035f * s, Offset(x * s, y * s))
    }
}

private fun DrawScope.snowflake(center: Offset, radius: Float) {
    for (i in 0 until 3) {
        val angle = (i * PI / 3 + PI / 2).toFloat()
        val dir = Offset(cos(angle), sin(angle)) * radius
        drawLine(Color.White, center - dir, center + dir, strokeWidth = radius * 0.42f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.bolt(s: Float) {
    val path = Path().apply {
        moveTo(.57f * s, .48f * s)
        lineTo(.40f * s, .74f * s)
        lineTo(.51f * s, .74f * s)
        lineTo(.45f * s, .96f * s)
        lineTo(.68f * s, .64f * s)
        lineTo(.56f * s, .64f * s)
        lineTo(.64f * s, .48f * s)
        close()
    }
    drawPath(path, BoltColor)
}

private fun DrawScope.fogLines(s: Float) {
    val color = Color.White.copy(alpha = 0.8f)
    listOf(Triple(.14f, .86f, .64f), Triple(.24f, .80f, .76f), Triple(.18f, .70f, .88f)).forEach { (from, to, y) ->
        drawLine(color, Offset(from * s, y * s), Offset(to * s, y * s), strokeWidth = .06f * s, cap = StrokeCap.Round)
    }
}
