package com.mindfullness.weather.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mindfullness.weather.R
import com.mindfullness.weather.domain.AlertType
import com.mindfullness.weather.domain.TipKind
import java.util.Locale

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private val TR: Locale = Locale.forLanguageTag("tr-TR")

fun Context.dp(value: Number): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
).toInt()

fun View.dp(value: Number): Int = context.dp(value)

object Fonts {
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
}

fun Context.text(
    value: CharSequence,
    sizeSp: Float,
    color: Int = Palette.TEXT,
    typeface: Typeface = Fonts.regular,
    maxLines: Int = Int.MAX_VALUE,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    this.typeface = typeface
    includeFontPadding = false
    setLineSpacing(0f, 1.15f)
    if (maxLines != Int.MAX_VALUE) {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Round icon button with a ripple, 48dp touch target. */
fun Context.iconButton(res: Int, description: String, tint: Int = Palette.TEXT, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        background = ripple(null, oval = true)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun ripple(content: Drawable?, oval: Boolean = false, radius: Float = 0f): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
    }
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
}

/** Rounded translucent card with a coloured stripe on the leading edge, clipped to the corners. */
class AccentCardDrawable(
    private val fillColor: Int,
    private val borderColor: Int,
    private val accentColor: Int,
    private val radius: Float,
    private val stripeWidth: Float,
    private val borderWidth: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawRect(rect, paint)
        paint.color = accentColor
        canvas.drawRect(rect.left, rect.top, rect.left + stripeWidth, rect.bottom, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = borderWidth
        paint.color = borderColor
        rect.inset(borderWidth / 2, borderWidth / 2)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

fun Context.glassBackground(radiusDp: Int = 24): Drawable =
    rounded(Palette.GLASS, dp(radiusDp).toFloat(), Palette.GLASS_BORDER, dp(1))

fun Context.vertical(paddingDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (paddingDp > 0) setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}

fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.space(widthDp: Int = 0, heightDp: Int = 0): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
}

fun <T : View> T.params(
    width: Int = MATCH,
    height: Int = WRAP,
    weight: Float = 0f,
    top: Int = 0,
    bottom: Int = 0,
    start: Int = 0,
    end: Int = 0,
    gravity: Int = -1,
): T {
    layoutParams = LinearLayout.LayoutParams(width, height, weight).apply {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        if (gravity != -1) this.gravity = gravity
    }
    return this
}

fun <T : View> T.frameParams(width: Int = MATCH, height: Int = WRAP, gravity: Int = Gravity.TOP): T {
    layoutParams = FrameLayout.LayoutParams(width, height, gravity)
    return this
}

/** Small uppercase heading with an icon, used above every section. */
fun Context.sectionTitle(iconRes: Int, title: String, trailing: String? = null): LinearLayout = horizontal().apply {
    addView(icon(iconRes, Palette.TEXT_TERTIARY, 16))
    addView(space(widthDp = 6))
    addView(text(title.uppercase(TR), 12f, Palette.TEXT_TERTIARY, Fonts.medium).apply { letterSpacing = 0.06f }, LinearLayout.LayoutParams(0, WRAP, 1f))
    if (trailing != null) addView(text(trailing, 12f, Palette.TEXT_TERTIARY, Fonts.medium))
}

fun Context.pill(value: String, background: Int, foreground: Int): TextView =
    text(value, 11f, foreground, Fonts.medium).apply {
        this.background = rounded(background, dp(50).toFloat())
        setPadding(dp(8), dp(3), dp(8), dp(4))
    }

object Icons {
    fun alert(type: AlertType): Int = when (type) {
        AlertType.RAIN -> R.drawable.ic_umbrella
        AlertType.SNOW, AlertType.COLD, AlertType.ICE -> R.drawable.ic_snowflake
        AlertType.THUNDERSTORM -> R.drawable.ic_zap
        AlertType.WIND -> R.drawable.ic_wind
        AlertType.HEAT -> R.drawable.ic_thermometer
        AlertType.FOG -> R.drawable.ic_fog
        AlertType.UV -> R.drawable.ic_sun
        AlertType.TEMPERATURE_DROP -> R.drawable.ic_trending_down
    }

    fun tip(kind: TipKind): Int = when (kind) {
        TipKind.UMBRELLA -> R.drawable.ic_umbrella
        TipKind.WARM_COAT, TipKind.JACKET -> R.drawable.ic_shirt
        TipKind.SUNSCREEN -> R.drawable.ic_sun
        TipKind.SUNGLASSES -> R.drawable.ic_glasses
        TipKind.WATER -> R.drawable.ic_droplet
        TipKind.BOOTS -> R.drawable.ic_snowflake
        TipKind.WINDBREAKER -> R.drawable.ic_wind
    }
}

fun Double.deg(): String = "${Math.round(this)}°"
