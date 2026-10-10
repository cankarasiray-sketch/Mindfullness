package com.mindfullness.weather.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Page indicator of the home screen: one dot per place, a small arrow for the device location. */
class PageDots(context: Context) : View(context) {
    private var count = 0
    private var index = -1
    private var firstIsLocation = false
    private val size = dp(7).toFloat()
    private val gap = dp(9).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()

    init {
        // The location arrow is a little wider than a dot.
        val overhang = kotlin.math.ceil(size * 0.15f).toInt()
        setPadding(overhang, overhang, overhang, overhang)
    }

    fun set(pager: PagerInfo) {
        if (pager.count == count && pager.index == index && pager.firstIsLocation == firstIsLocation) return
        count = pager.count
        index = pager.index
        firstIsLocation = pager.firstIsLocation
        contentDescription = if (index >= 0) {
            "Yer ${index + 1} / $count. Diğer yerler için sağa veya sola kaydırın."
        } else {
            "Diğer yerler için sağa veya sola kaydırın."
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (count == 0) 0f else count * size + (count - 1) * gap
        setMeasuredDimension(
            resolveSize((width + paddingLeft + paddingRight).toInt(), widthMeasureSpec),
            resolveSize((size + paddingTop + paddingBottom).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = paddingTop + size / 2
        for (i in 0 until count) {
            paint.color = if (i == index) Color.WHITE else Color.argb(110, 255, 255, 255)
            val cx = paddingLeft + i * (size + gap) + size / 2
            if (i == 0 && firstIsLocation) {
                // Navigation arrow, like the location icon in the title.
                val r = size * 0.62f
                arrow.reset()
                arrow.moveTo(cx - r, cy - r * 0.05f)
                arrow.lineTo(cx + r, cy - r)
                arrow.lineTo(cx + r * 0.05f, cy + r)
                arrow.lineTo(cx - r * 0.05f, cy + r * 0.05f)
                arrow.close()
                canvas.drawPath(arrow, paint)
            } else {
                canvas.drawCircle(cx, cy, size / 2, paint)
            }
        }
    }
}
