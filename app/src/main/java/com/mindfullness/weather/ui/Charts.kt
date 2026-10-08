package com.mindfullness.weather.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.HourlyPoint
import kotlin.math.max

/** 24-hour precipitation bars (amount) with the probability drawn as a line, plus hour labels. */
class PrecipitationChartView(context: Context, private val hours: List<HourlyPoint>) : View(context) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(1).toFloat() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(178, 255, 255, 255)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_TERTIARY
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
        typeface = Fonts.medium
    }
    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(92 + 22))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hours.isEmpty()) return
        val chartHeight = height - dp(22).toFloat()
        val slot = width.toFloat() / hours.size
        val barWidth = slot * 0.62f
        val maxAmount = max(2.0, hours.maxOf { it.precipitation })

        grid.color = Color.argb(20, 255, 255, 255)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, grid)
        canvas.drawLine(0f, chartHeight / 2, width.toFloat(), chartHeight / 2, grid)
        grid.color = Color.argb(51, 255, 255, 255)
        canvas.drawLine(0f, chartHeight, width.toFloat(), chartHeight, grid)

        hours.forEachIndexed { index, hour ->
            if (hour.precipitation > 0.0) {
                val h = (hour.precipitation / maxAmount).toFloat().coerceIn(0.04f, 1f) * chartHeight
                bar.color = if (AlertEngine.isSnowy(hour)) Color.WHITE else Palette.RAIN
                val left = index * slot + (slot - barWidth) / 2
                rect.set(left, chartHeight - h, left + barWidth, chartHeight)
                canvas.drawRoundRect(rect, barWidth / 3, barWidth / 3, bar)
            }
        }

        if (hours.any { it.precipitationProbability != null }) {
            path.reset()
            hours.forEachIndexed { index, hour ->
                val x = index * slot + slot / 2
                val y = chartHeight * (1f - (hour.precipitationProbability ?: 0) / 100f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
        }

        val baseline = height - dp(4).toFloat()
        hours.forEachIndexed { index, hour ->
            if (index % 4 == 0) {
                val x = (index * slot + slot / 2).coerceIn(dp(14).toFloat(), width - dp(14).toFloat())
                canvas.drawText(if (index == 0) "Şimdi" else hour.time.hour.toString().padStart(2, '0'), x, baseline, label)
            }
        }
    }
}

/** Min–max temperature bar placed on the scale of the whole week. */
class TemperatureRangeView(
    context: Context,
    private val low: Double,
    private val high: Double,
    private val weekMin: Double,
    private val weekMax: Double,
) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(6))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, track)
        val span = (weekMax - weekMin).coerceAtLeast(1.0)
        val start = ((low - weekMin) / span).toFloat() * width
        val end = max(start + height, ((high - weekMin) / span).toFloat() * width)
        fill.shader = LinearGradient(start, 0f, end, 0f, temperatureColor(low), temperatureColor(high), Shader.TileMode.CLAMP)
        rect.set(start, 0f, end, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    private fun temperatureColor(t: Double): Int {
        val cold = Color.parseColor("#6CC4FF")
        val mild = Color.parseColor("#8BE0A4")
        val warm = Color.parseColor("#FFC940")
        val hot = Color.parseColor("#FF7A45")
        return when {
            t <= 0 -> cold
            t <= 15 -> blend(cold, mild, t / 15)
            t <= 25 -> blend(mild, warm, (t - 15) / 10)
            t <= 35 -> blend(warm, hot, (t - 25) / 10)
            else -> hot
        }
    }

    private fun blend(a: Int, b: Int, fraction: Double): Int {
        val f = fraction.toFloat().coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }
}
