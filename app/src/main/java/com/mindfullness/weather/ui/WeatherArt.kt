package com.mindfullness.weather.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.mindfullness.weather.domain.Condition
import com.mindfullness.weather.domain.WeatherCodes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Colourful, resolution independent weather illustration. */
class WeatherIconView(context: Context, code: Int = 0, isDay: Boolean = true) : View(context) {
    var code: Int = code
        set(value) { field = value; invalidate() }
    var isDay: Boolean = isDay
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        WeatherArt.draw(canvas, code, isDay, s)
        canvas.restore()
    }
}

object WeatherArt {
    private val SUN_CORE = intArrayOf(Color.parseColor("#FFE680"), Color.parseColor("#FFB300"))
    private val SUN_RAY = Color.parseColor("#FFC233")
    private val MOON = Color.parseColor("#F3E6BD")
    private val CLOUD_LIGHT = intArrayOf(Color.WHITE, Color.parseColor("#DCE4EE"))
    private val CLOUD_GREY = intArrayOf(Color.parseColor("#D4DCE6"), Color.parseColor("#A9B5C4"))
    private val CLOUD_DARK = intArrayOf(Color.parseColor("#9AA6B8"), Color.parseColor("#6B788C"))
    private val RAIN = Color.parseColor("#6CC4FF")
    private val BOLT = Color.parseColor("#FFD43B")
    private val HAIL = Color.parseColor("#E3F2FF")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    fun draw(canvas: Canvas, code: Int, isDay: Boolean, s: Float) {
        when (WeatherCodes.condition(code)) {
            Condition.CLEAR -> if (isDay) {
                sun(canvas, .5f * s, .5f * s, .2f * s)
            } else {
                moon(canvas, .5f * s, .5f * s, .27f * s)
                sparkle(canvas, .8f * s, .22f * s, .06f * s)
                sparkle(canvas, .2f * s, .78f * s, .045f * s)
            }
            Condition.MOSTLY_CLEAR -> {
                celestial(canvas, isDay, .42f * s, .4f * s, .19f * s)
                cloud(canvas, .40f * s, .52f * s, .54f * s, CLOUD_LIGHT)
            }
            Condition.PARTLY_CLOUDY -> {
                celestial(canvas, isDay, .36f * s, .34f * s, .16f * s)
                cloud(canvas, .18f * s, .36f * s, .76f * s, CLOUD_LIGHT)
            }
            Condition.OVERCAST -> {
                cloud(canvas, .06f * s, .16f * s, .62f * s, CLOUD_GREY)
                cloud(canvas, .24f * s, .34f * s, .70f * s, CLOUD_LIGHT)
            }
            Condition.FOG -> {
                cloud(canvas, .16f * s, .06f * s, .68f * s, CLOUD_GREY)
                fogLines(canvas, s)
            }
            Condition.DRIZZLE -> {
                cloud(canvas, .1f * s, .06f * s, .8f * s, CLOUD_LIGHT)
                drizzle(canvas, s)
            }
            Condition.RAIN -> {
                cloud(canvas, .1f * s, .06f * s, .8f * s, CLOUD_GREY)
                rain(canvas, s, 3)
            }
            Condition.HEAVY_RAIN -> {
                cloud(canvas, .1f * s, .06f * s, .8f * s, CLOUD_DARK)
                rain(canvas, s, 4)
            }
            Condition.SHOWERS -> {
                // Placed so the top ray (1.85 r plus its round cap) stays inside the view.
                if (isDay) celestial(canvas, true, .66f * s, .30f * s, .14f * s)
                cloud(canvas, .08f * s, .1f * s, .74f * s, CLOUD_GREY)
                rain(canvas, s, 3)
            }
            Condition.FREEZING_RAIN, Condition.SLEET -> {
                cloud(canvas, .1f * s, .06f * s, .8f * s, CLOUD_GREY)
                rainDrop(canvas, .34f * s, .66f * s, s)
                snowflake(canvas, .52f * s, .78f * s, .07f * s)
                rainDrop(canvas, .70f * s, .66f * s, s)
            }
            Condition.SNOW -> {
                cloud(canvas, .1f * s, .06f * s, .8f * s, CLOUD_LIGHT)
                snowflake(canvas, .30f * s, .72f * s, .075f * s)
                snowflake(canvas, .52f * s, .86f * s, .075f * s)
                snowflake(canvas, .72f * s, .70f * s, .075f * s)
            }
            Condition.THUNDERSTORM -> {
                cloud(canvas, .1f * s, .04f * s, .8f * s, CLOUD_DARK)
                bolt(canvas, s)
                rainDrop(canvas, .30f * s, .68f * s, s)
                rainDrop(canvas, .78f * s, .68f * s, s)
            }
            Condition.HAIL -> {
                cloud(canvas, .1f * s, .04f * s, .8f * s, CLOUD_DARK)
                bolt(canvas, s)
                fill.shader = null
                fill.color = HAIL
                canvas.drawCircle(.28f * s, .74f * s, .04f * s, fill)
                canvas.drawCircle(.80f * s, .72f * s, .04f * s, fill)
                canvas.drawCircle(.30f * s, .90f * s, .035f * s, fill)
            }
        }
    }

    private fun celestial(canvas: Canvas, isDay: Boolean, cx: Float, cy: Float, r: Float) {
        if (isDay) sun(canvas, cx, cy, r) else moon(canvas, cx, cy, r * 1.25f)
    }

    private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.shader = null
        stroke.color = SUN_RAY
        stroke.strokeWidth = r * 0.26f
        for (i in 0 until 8) {
            val angle = i * PI / 4
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            canvas.drawLine(cx + dx * r * 1.42f, cy + dy * r * 1.42f, cx + dx * r * 1.85f, cy + dy * r * 1.85f, stroke)
        }
        fill.shader = RadialGradient(cx, cy, r, SUN_CORE, null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW) }
        outer.op(cut, Path.Op.DIFFERENCE)
        fill.shader = null
        fill.color = MOON
        canvas.drawPath(outer, fill)
    }

    private fun sparkle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val path = Path().apply {
            moveTo(cx, cy - r)
            quadTo(cx, cy, cx + r, cy)
            quadTo(cx, cy, cx, cy + r)
            quadTo(cx, cy, cx - r, cy)
            quadTo(cx, cy, cx, cy - r)
            close()
        }
        fill.shader = null
        fill.color = MOON
        canvas.drawPath(path, fill)
    }

    /** Cloud whose bounding box starts at ([left], [top]) and is [width] wide, ~0.62 * width tall. */
    private fun cloud(canvas: Canvas, left: Float, top: Float, width: Float, colors: IntArray) {
        fill.shader = LinearGradient(0f, top, 0f, top + width * 0.62f, colors, null, Shader.TileMode.CLAMP)
        rect.set(left + width * 0.05f, top + width * 0.35f, left + width * 0.95f, top + width * 0.62f)
        canvas.drawRoundRect(rect, width * 0.135f, width * 0.135f, fill)
        canvas.drawCircle(left + width * 0.56f, top + width * 0.31f, width * 0.28f, fill)
        canvas.drawCircle(left + width * 0.29f, top + width * 0.41f, width * 0.19f, fill)
        fill.shader = null
    }

    private fun rainDrop(canvas: Canvas, x: Float, y: Float, s: Float) {
        stroke.shader = null
        stroke.color = RAIN
        stroke.strokeWidth = .055f * s
        canvas.drawLine(x, y, x - .05f * s, y + .15f * s, stroke)
    }

    private fun rain(canvas: Canvas, s: Float, drops: Int) {
        val xs = if (drops == 4) floatArrayOf(.26f, .44f, .62f, .80f) else floatArrayOf(.32f, .52f, .72f)
        xs.forEachIndexed { index, x -> rainDrop(canvas, x * s, (if (index % 2 == 0) .64f else .74f) * s, s) }
    }

    private fun drizzle(canvas: Canvas, s: Float) {
        fill.shader = null
        fill.color = RAIN
        val points = floatArrayOf(.32f, .68f, .52f, .76f, .72f, .68f, .42f, .88f, .62f, .88f)
        for (i in points.indices step 2) canvas.drawCircle(points[i] * s, points[i + 1] * s, .035f * s, fill)
    }

    private fun snowflake(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        stroke.shader = null
        stroke.color = Color.WHITE
        stroke.strokeWidth = r * 0.42f
        for (i in 0 until 3) {
            val angle = i * PI / 3 + PI / 2
            val dx = (cos(angle) * r).toFloat()
            val dy = (sin(angle) * r).toFloat()
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke)
        }
    }

    private fun bolt(canvas: Canvas, s: Float) {
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
        fill.shader = null
        fill.color = BOLT
        canvas.drawPath(path, fill)
    }

    private fun fogLines(canvas: Canvas, s: Float) {
        stroke.shader = null
        stroke.color = Color.argb(204, 255, 255, 255)
        stroke.strokeWidth = .06f * s
        canvas.drawLine(.14f * s, .64f * s, .86f * s, .64f * s, stroke)
        canvas.drawLine(.24f * s, .76f * s, .80f * s, .76f * s, stroke)
        canvas.drawLine(.18f * s, .88f * s, .70f * s, .88f * s, stroke)
    }
}
