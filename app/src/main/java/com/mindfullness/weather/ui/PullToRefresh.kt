package com.mindfullness.weather.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import com.mindfullness.weather.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Pull-to-refresh for a vertical [ScrollView] (the framework has no SwipeRefreshLayout): dragging
 * down while the list is at its top pulls in a refresh disc, and releasing past the threshold
 * calls [onRefresh]. Progress itself is shown by the caller (the spinning refresh button).
 */
class PullToRefreshLayout(
    context: Context,
    private val scroll: ScrollView,
    private val onRefresh: () -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = dp(72).toFloat()
    private val size = dp(40)
    private val arrow = context.icon(R.drawable.ic_refresh, Palette.TEXT, 20)
    private val indicator = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.SURFACE_HIGH)
            setStroke(dp(1), Palette.GLASS_BORDER)
        }
        elevation = dp(6).toFloat()
        addView(arrow, LayoutParams(dp(20), dp(20), Gravity.CENTER))
        contentDescription = "Yenile"
    }
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var pull = 0f

    /** Where the disc rests when fully pulled, below the top bar. */
    var restingTop = 0

    init {
        addView(scroll, LayoutParams(MATCH, MATCH))
        addView(indicator, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        hide(animate = false)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        track(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                pull = ((event.y - startY) * 0.5f).coerceAtLeast(0f)
                show(pull)
            }
            MotionEvent.ACTION_UP -> finish(dragging && pull >= threshold)
            MotionEvent.ACTION_CANCEL -> finish(false)
        }
        // Keep the gesture once it reached us: nothing below wanted it.
        return true
    }

    private fun track(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
                pull = 0f
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && canStart()) {
                val dy = event.y - startY
                if (dy > touchSlop && dy > abs(event.x - startX)) {
                    dragging = true
                    startY = event.y
                }
            }
        }
    }

    private fun canStart(): Boolean =
        isEnabled && scroll.visibility == View.VISIBLE && !scroll.canScrollVertically(-1)

    private fun show(distance: Float) {
        indicator.animate().cancel()
        val progress = min(1f, distance / threshold)
        indicator.alpha = progress
        indicator.translationY = restingTop * progress - size + min(distance, threshold * 1.4f) * 0.4f
        indicator.rotation = distance * 1.6f
        arrow.alpha = if (progress >= 1f) 1f else 0.6f
    }

    private fun finish(trigger: Boolean) {
        if (trigger) {
            onRefresh()
            // Spin once more before tucking away; the top bar shows the rest of the progress.
            indicator.animate().rotationBy(360f).setDuration(450).withEndAction { hide(animate = true) }.start()
        } else if (dragging) {
            hide(animate = true)
        }
        dragging = false
        pull = 0f
    }

    private fun hide(animate: Boolean) {
        val target = -size.toFloat() - dp(8)
        if (animate) {
            indicator.animate().translationY(target).alpha(0f).setDuration(200).start()
        } else {
            indicator.translationY = target
            indicator.alpha = 0f
        }
    }
}
