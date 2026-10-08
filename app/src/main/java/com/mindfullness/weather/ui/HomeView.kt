package com.mindfullness.weather.ui

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mindfullness.weather.R
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
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

class HomeView(context: Context, private val actions: Actions) : FrameLayout(context) {

    interface Actions {
        fun refresh()
        fun openPlaces()
        fun openSettings()
        fun useLocation()
    }

    private val sky = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Palette.sky(null, true))
    private val scroll = ScrollView(context)
    private val content = context.vertical()
    private val banner = context.horizontal()
    private val bannerText = context.text("", 14f)
    private val overlay = FrameLayout(context)
    private val topBar = context.horizontal()
    private val title = context.text("", 20f, Palette.TEXT, Fonts.medium, maxLines = 1)
    private val subtitle = context.text("", 12f, Palette.TEXT_SECONDARY, maxLines = 1)
    private val placeIcon = context.icon(R.drawable.ic_navigation, Palette.TEXT, 16)
    private val refreshButton = context.iconButton(R.drawable.ic_refresh, "Yenile") { actions.refresh() }
    private var refreshAnimator: ObjectAnimator? = null

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var locateButton: View? = null
    private var locateLabel: TextView? = null
    private var renderedContent: ForecastContent? = null
    private var renderedPlaceId: Long? = null
    private var overlayKey: String? = null

    /** Remembers which alert cards / days the user expanded across re-renders. */
    private val expanded = HashMap<String, Boolean>()

    init {
        background = sky
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(content, LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, MATCH))
        addView(overlay, LayoutParams(MATCH, MATCH))
        buildBanner()
        buildTopBar()
        addView(topBar, LayoutParams(MATCH, WRAP, Gravity.TOP))
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = bars.top
                insetBottom = bars.bottom
                insetLeft = bars.left
                insetRight = bars.right
            } else {
                @Suppress("DEPRECATION")
                insetTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                insetLeft = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                insetRight = insets.systemWindowInsetRight
            }
            applyInsets()
            insets
        }
    }

    // region Rendering ---------------------------------------------------------------------------

    fun render(state: HomeState) {
        val current = state.content?.forecast?.current
        sky.colors = Palette.sky(current?.weatherCode, current?.isDay ?: true)

        topBar.visibility = if (state.needsOnboarding) View.GONE else View.VISIBLE
        title.text = state.place?.name ?: "Konum seçin"
        val sub = state.place?.subtitle.orEmpty()
        subtitle.text = sub
        subtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        placeIcon.visibility = if (state.place?.isCurrentLocation == true) View.VISIBLE else View.GONE
        setSpinning(state.isRefreshing || state.isLocating || (state.isLoading && state.content != null))

        if (state.place?.id != renderedPlaceId) expanded.clear()
        val data = state.content
        when {
            state.needsOnboarding -> {
                showOverlay("welcome") { welcome() }
                locateLabel?.text = if (state.isLocating) "Konum bulunuyor…" else "Konumumu kullan"
                locateButton?.isEnabled = !state.isLocating
                locateButton?.alpha = if (state.isLocating) 0.7f else 1f
            }
            data == null && (state.isLoading || state.isLocating) -> showOverlay("loading-${state.isLocating}") {
                loading(if (state.isLocating) "Konumunuz bulunuyor…" else "Hava durumu yükleniyor…")
            }
            data == null -> showOverlay("error") { error(state.errorMessage) }
            else -> {
                showOverlay(null) { null }
                if (data !== renderedContent || state.place?.id != renderedPlaceId) {
                    val placeChanged = state.place?.id != renderedPlaceId
                    rebuild(data)
                    renderedContent = data
                    renderedPlaceId = state.place?.id
                    if (placeChanged) scroll.scrollTo(0, 0)
                }
                updateBanner(state, data)
            }
        }
        if (state.needsOnboarding || data == null) {
            renderedContent = null
            renderedPlaceId = null
            content.removeAllViews()
        }
    }

    private fun showOverlay(key: String?, build: () -> View?) {
        if (key == overlayKey) return
        overlayKey = key
        overlay.removeAllViews()
        val view = build()
        if (view != null) {
            overlay.addView(view, LayoutParams(MATCH, MATCH))
            overlay.visibility = View.VISIBLE
            scroll.visibility = View.GONE
        } else {
            overlay.visibility = View.GONE
            scroll.visibility = View.VISIBLE
        }
        applyInsets()
    }

    private fun applyInsets() {
        topBar.setPadding(insetLeft + dp(8), insetTop + dp(6), insetRight + dp(4), dp(10))
        content.setPadding(insetLeft + dp(16), insetTop + dp(76), insetRight + dp(16), insetBottom + dp(28))
        for (i in 0 until overlay.childCount) {
            overlay.getChildAt(i).setPadding(insetLeft + dp(24), insetTop + dp(24), insetRight + dp(24), insetBottom + dp(24))
        }
    }

    private fun setSpinning(spinning: Boolean) {
        if (spinning && refreshAnimator == null) {
            refreshAnimator = ObjectAnimator.ofFloat(refreshButton, View.ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else if (!spinning && refreshAnimator != null) {
            refreshAnimator?.cancel()
            refreshAnimator = null
            refreshButton.animate().rotation(0f).setDuration(200).start()
        }
        refreshButton.isEnabled = !spinning
        refreshButton.alpha = if (spinning) 0.8f else 1f
    }

    private fun rebuild(data: ForecastContent) {
        val forecast = data.forecast
        val now = data.now
        val today = forecast.today(now)
        content.removeAllViews()
        content.addView(banner)
        val gap = 18
        if (forecast.hoursFrom(now, 1).isEmpty()) {
            // A cache older than its 10-day range would otherwise read as "nothing to worry about".
            content.addView(outdatedCard())
            return
        }
        content.addView(hero(forecast.current, today).params(bottom = gap, top = 4))
        content.addView(alertsSection(data.alerts, now).params(bottom = gap))
        if (data.tips.isNotEmpty()) content.addView(tipsSection(data.tips).params(bottom = gap))
        content.addView(precipitationCard(data.precipitation, forecast.precipitationHours(now, 24)).params(bottom = gap))
        content.addView(hourlyCard(forecast.hoursFrom(now, 36), forecast.current).params(bottom = gap))
        content.addView(
            dailyCard(forecast.daily.filter { !it.date.isBefore(now.toLocalDate()) }, now.toLocalDate(), alertDays(data.alerts))
                .params(bottom = gap),
        )
        content.addView(detailsSection(forecast.current, forecast.hourAt(now), today).params(bottom = gap))
        content.addView(
            context.text(
                "Veri: Open-Meteo.com (CC BY 4.0) · Son güncelleme ${TimeText.ago(forecast.fetchedAtMillis)}\n" +
                    "Uyarılar tahmin modellerine dayanır; resmî uyarılar için MGM ve AFAD'ı takip edin.",
                12f, Palette.TEXT_TERTIARY,
            ).apply { gravity = Gravity.CENTER }.params(top = 4),
        )
    }

    // endregion
    // region Top bar & banner -----------------------------------------------------------------

    private fun buildTopBar() {
        topBar.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(90, 0, 0, 0), Color.TRANSPARENT),
        )
        // The chevron is part of the title so ellipsizing a long name keeps room for it.
        val chevron = context.getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
            setTint(Palette.TEXT_SECONDARY)
            setBounds(0, 0, dp(20), dp(20))
        }
        title.setCompoundDrawables(null, null, chevron, null)
        title.compoundDrawablePadding = dp(4)
        val placeBlock = context.horizontal().apply {
            background = ripple(null, radius = dp(16).toFloat())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minimumHeight = dp(48)
            contentDescription = "Konum değiştir"
            setOnClickListener { actions.openPlaces() }
            addView(placeIcon)
            addView(context.space(widthDp = 6))
            val texts = context.vertical()
            texts.addView(title)
            texts.addView(subtitle.params(top = 2))
            addView(texts, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        placeIcon.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        val placeHolder = FrameLayout(context)
        placeHolder.addView(placeBlock, LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        topBar.addView(placeHolder, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(refreshButton)
        topBar.addView(context.iconButton(R.drawable.ic_search, "Yer ara") { actions.openPlaces() })
        topBar.addView(context.iconButton(R.drawable.ic_settings, "Ayarlar") { actions.openSettings() })
    }

    private fun buildBanner() {
        banner.background = rounded(Palette.GLASS_STRONG, dp(14).toFloat())
        banner.setPadding(dp(14), dp(10), dp(14), dp(10))
        banner.addView(context.icon(R.drawable.ic_alert, Palette.TEXT, 18))
        banner.addView(context.space(widthDp = 10))
        banner.addView(bannerText, LinearLayout.LayoutParams(0, WRAP, 1f))
        banner.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        banner.setOnClickListener { actions.refresh() }
    }

    private fun updateBanner(state: HomeState, data: ForecastContent) {
        val failed = state.errorMessage != null
        val visible = failed || data.isStale
        banner.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            banner.background = rounded(if (failed) Palette.WARNING_SURFACE else Palette.GLASS_STRONG, dp(14).toFloat())
            bannerText.text = (if (failed) "Güncellenemedi" else "Veriler eski") +
                " · son güncelleme ${TimeText.ago(data.forecast.fetchedAtMillis)} · yenilemek için dokunun"
        }
    }

    // endregion
    // region Sections ---------------------------------------------------------------------------

    private fun hero(current: CurrentWeather, today: DailyPoint?): View = context.vertical().apply {
        setPadding(dp(8), 0, dp(8), 0)
        val row = context.horizontal()
        val temperature = context.text(current.temperature.deg(), 96f, Palette.TEXT, Fonts.thin, maxLines = 1).apply {
            // Shrinks "-12°" on narrow screens or large font scales instead of wrapping the "°".
            ellipsize = null
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(40, 96, 2, TypedValue.COMPLEX_UNIT_SP)
        }
        row.addView(temperature, LinearLayout.LayoutParams(0, dp(120), 1f))
        row.addView(WeatherIconView(context, current.weatherCode, current.isDay), LinearLayout.LayoutParams(dp(120), dp(120)))
        addView(row)
        addView(context.text(WeatherCodes.describe(current.weatherCode), 22f, Palette.TEXT, Fonts.medium))
        val range = today?.let { "En yüksek ${it.temperatureMax.deg()} · En düşük ${it.temperatureMin.deg()}" }
        addView(
            context.text(listOfNotNull(range, "Hissedilen ${current.apparentTemperature.deg()}").joinToString("  ·  "), 16f, Palette.TEXT_SECONDARY)
                .params(top = 6),
        )
    }

    private fun alertsSection(alerts: List<WeatherAlert>, now: LocalDateTime): View = context.vertical().apply {
        layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        addView(context.sectionTitle(R.drawable.ic_bell, "Dikkat edilmesi gerekenler", "48 saat").params(start = 4, end = 4, bottom = 10))
        val upcoming = alerts.filter { !it.outlook }
        val outlook = alerts.filter { it.outlook }
        if (upcoming.none { it.severity >= Severity.YELLOW }) addView(allClearCard(upcoming.isNotEmpty()).params(bottom = 10))
        val firstImportant = upcoming.firstOrNull { it.severity >= Severity.YELLOW }
        upcoming.forEach { addView(alertCard(it, now, defaultExpanded = it == firstImportant).params(bottom = 10)) }
        if (outlook.isNotEmpty()) {
            addView(context.text("İleriki günler", 14f, Palette.TEXT_SECONDARY, Fonts.medium).params(start = 4, top = 6, bottom = 10))
            outlook.forEach { addView(alertCard(it, now, defaultExpanded = false).params(bottom = 10)) }
        }
    }

    private fun allClearCard(hasInfo: Boolean): View = context.horizontal().apply {
        background = context.glassBackground()
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(circleIcon(R.drawable.ic_check_circle, Palette.GOOD))
        addView(context.space(widthDp = 14))
        val texts = context.vertical()
        texts.addView(context.text("Önemli bir hava olayı beklenmiyor", 16f, Palette.TEXT, Fonts.medium))
        texts.addView(
            context.text(
                if (hasInfo) "Aşağıdaki küçük notlara göz atmanız yeterli." else "Önümüzdeki 48 saat için uyarı yok.",
                14f, Palette.TEXT_SECONDARY,
            ).params(top = 3),
        )
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun circleIcon(res: Int, tint: Int, sizeDp: Int = 40, iconDp: Int = 22): View = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.withAlpha(tint, 0.18f))
        }
        addView(context.icon(res, tint, iconDp).frameParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun alertCard(alert: WeatherAlert, now: LocalDateTime, defaultExpanded: Boolean): View {
        val key = "alert-${alert.key}-${alert.end}"
        val accent = Palette.severity(alert.severity)
        val radius = dp(24).toFloat()
        val card = context.vertical().apply {
            background = ripple(
                AccentCardDrawable(Palette.GLASS, Palette.withAlpha(accent, 0.35f), accent, radius, dp(5).toFloat(), dp(1).toFloat()),
                radius = radius,
            )
        }
        val body = context.vertical().apply { setPadding(dp(18), dp(14), dp(14), dp(10)) }
        card.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        val header = context.horizontal()
        header.addView(circleIcon(Icons.alert(alert.type), accent))
        header.addView(context.space(widthDp = 12))
        val titles = context.vertical()
        titles.addView(context.text(alert.title, 16f, Palette.TEXT, Fonts.medium))
        titles.addView(context.text(alert.whenText(now), 12f, Palette.TEXT_SECONDARY).params(top = 3))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(context.space(widthDp = 8))
        header.addView(context.pill(alert.severity.shortLabel, accent, Palette.ON_SEVERITY))
        body.addView(header)
        body.addView(context.text(alert.detail, 14f, Palette.TEXT_SECONDARY).params(top = 10))

        val advice = context.vertical().apply { setPadding(0, dp(12), 0, 0) }
        val adviceHeader = context.horizontal()
        adviceHeader.addView(context.icon(R.drawable.ic_shield, accent, 16))
        adviceHeader.addView(context.space(widthDp = 6))
        adviceHeader.addView(context.text("Ne yapmalı?", 14f, Palette.TEXT, Fonts.medium), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (alert.severity != Severity.INFO) adviceHeader.addView(context.text(alert.severity.label, 11f, Palette.TEXT_TERTIARY))
        advice.addView(adviceHeader.params(bottom = 4))
        alert.advice.forEach { line ->
            val row = context.horizontal().apply { gravity = Gravity.TOP }
            row.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(7) })
            row.addView(context.space(widthDp = 10))
            row.addView(context.text(line, 14f, Palette.TEXT), LinearLayout.LayoutParams(0, WRAP, 1f))
            advice.addView(row.params(top = 6))
        }
        body.addView(advice)

        val toggleText = context.text("", 12f, Palette.TEXT_TERTIARY, Fonts.medium)
        val toggleIcon = context.icon(R.drawable.ic_chevron_down, Palette.TEXT_TERTIARY, 18)
        val footer = context.horizontal().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(toggleText)
            addView(toggleIcon)
        }
        body.addView(footer.params(top = 6))

        fun apply(open: Boolean) {
            advice.visibility = if (open) View.VISIBLE else View.GONE
            toggleText.text = if (open) "Gizle" else "Ne yapmalı?"
            toggleIcon.setImageResource(if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
        }
        apply(expanded[key] ?: defaultExpanded)
        card.setOnClickListener {
            val open = !(expanded[key] ?: defaultExpanded)
            expanded[key] = open
            apply(open)
        }
        return card
    }

    private fun tipsSection(tips: List<Tip>): View = context.vertical().apply {
        addView(context.sectionTitle(R.drawable.ic_briefcase, "Yanınıza alın").params(start = 4, end = 4, bottom = 10))
        val row = context.horizontal()
        tips.forEach { tip ->
            val chip = context.horizontal().apply {
                background = rounded(Palette.GLASS_STRONG, dp(50).toFloat())
                setPadding(dp(14), dp(9), dp(16), dp(9))
                addView(context.icon(Icons.tip(tip.kind), Palette.TEXT, 18))
                addView(context.space(widthDp = 8))
                addView(context.text(tip.text, 14f, Palette.TEXT, Fonts.medium))
            }
            row.addView(chip.params(WRAP, WRAP, end = 8))
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        })
    }

    private fun card(paddingDp: Int = 16): LinearLayout = context.vertical().apply {
        background = context.glassBackground()
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    private fun precipitationCard(summary: PrecipitationSummary, hours: List<HourlyPoint>): View = card().apply {
        addView(context.sectionTitle(R.drawable.ic_umbrella, "Yağış · 24 saat").params(bottom = 10))
        addView(context.text(summary.headline, 16f, Palette.TEXT, Fonts.medium))
        summary.detail?.let { addView(context.text(it, 14f, Palette.TEXT_SECONDARY).params(top = 3)) }
        if (hours.isNotEmpty()) {
            addView(PrecipitationChartView(context, hours).params(top = 14))
            val legend = context.horizontal()
            legend.addView(View(context).apply { background = rounded(Palette.RAIN, dp(2).toFloat()) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            legend.addView(context.text("Miktar (mm)", 11f, Palette.TEXT_TERTIARY).params(WRAP, WRAP, start = 6, end = 16))
            legend.addView(View(context).apply { setBackgroundColor(Color.argb(178, 255, 255, 255)) }, LinearLayout.LayoutParams(dp(14), dp(2)))
            legend.addView(context.text("Olasılık (%)", 11f, Palette.TEXT_TERTIARY).params(WRAP, WRAP, start = 6))
            addView(legend.params(top = 8))
        }
    }

    private fun hourlyCard(hours: List<HourlyPoint>, current: CurrentWeather): View = context.vertical().apply {
        background = context.glassBackground()
        setPadding(0, dp(16), 0, dp(14))
        addView(context.sectionTitle(R.drawable.ic_clock, "Saatlik tahmin").params(start = 16, end = 16, bottom = 10))
        val row = context.horizontal().apply { setPadding(dp(10), 0, dp(10), 0) }
        hours.forEachIndexed { index, hour ->
            // "Şimdi" mirrors the current conditions shown in the header.
            val shown = if (index == 0) {
                hour.copy(temperature = current.temperature, weatherCode = current.weatherCode, isDay = current.isDay)
            } else {
                hour
            }
            row.addView(hourItem(shown, index == 0))
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        })
    }

    private fun hourItem(hour: HourlyPoint, isNow: Boolean): View = context.vertical().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, dp(10))
        if (isNow) background = rounded(Palette.GLASS_STRONG, dp(18).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(60), WRAP).apply { marginEnd = dp(2) }
        addView(
            context.text(if (isNow) "Şimdi" else TimeText.hour(hour.time), 12f, if (isNow) Palette.TEXT else Palette.TEXT_SECONDARY, Fonts.medium)
                .params(WRAP, WRAP),
        )
        addView(WeatherIconView(context, hour.weatherCode, hour.isDay), LinearLayout.LayoutParams(dp(34), dp(34)).apply { topMargin = dp(6) })
        addView(context.text(hour.temperature.deg(), 16f, Palette.TEXT, Fonts.medium).params(WRAP, WRAP, top = 6))
        val probability = hour.precipitationProbability ?: 0
        addView(
            context.text(if (probability >= 20) "%$probability" else " ", 11f, Palette.RAIN_TEXT, Fonts.medium).params(WRAP, WRAP, top = 4),
        )
    }

    private fun dailyCard(days: List<DailyPoint>, today: LocalDate, alertDays: Map<LocalDate, Severity>): View = card().apply {
        layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        if (days.isEmpty()) return@apply
        addView(context.sectionTitle(R.drawable.ic_calendar, "${days.size} günlük tahmin").params(bottom = 4))
        val weekMin = days.minOf { it.temperatureMin }
        val weekMax = days.maxOf { it.temperatureMax }
        days.forEachIndexed { index, day ->
            if (index > 0) addView(View(context).apply { setBackgroundColor(Palette.DIVIDER) }, LinearLayout.LayoutParams(MATCH, 1))
            addView(dayRow(day, today, weekMin, weekMax, alertDays[day.date]))
        }
    }

    private fun dayRow(day: DailyPoint, today: LocalDate, weekMin: Double, weekMax: Double, severity: Severity?): View {
        val key = "day-${day.date}"
        val container = context.vertical().apply {
            background = ripple(null, radius = dp(12).toFloat())
            setPadding(0, dp(10), 0, dp(10))
        }
        val row = context.horizontal()
        val label = context.horizontal()
        label.addView(context.text(TimeText.relativeShortDay(day.date, today), 15f, Palette.TEXT, Fonts.medium))
        if (severity != null && severity >= Severity.YELLOW) {
            label.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.severity(severity)) }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(5) })
        }
        row.addView(label, LinearLayout.LayoutParams(dp(66), WRAP))
        row.addView(WeatherIconView(context, day.weatherCode, true), LinearLayout.LayoutParams(dp(30), dp(30)))
        val probability = day.precipitationProbabilityMax ?: 0
        row.addView(
            context.text(if (probability >= 20) "%$probability" else "", 12f, Palette.RAIN_TEXT, Fonts.medium).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(46), WRAP),
        )
        row.addView(
            context.text(day.temperatureMin.deg(), 15f, Palette.TEXT_SECONDARY, Fonts.medium).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dp(36), WRAP),
        )
        row.addView(
            TemperatureRangeView(context, day.temperatureMin, day.temperatureMax, weekMin, weekMax),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10); marginEnd = dp(10) },
        )
        row.addView(context.text(day.temperatureMax.deg(), 15f, Palette.TEXT, Fonts.medium), LinearLayout.LayoutParams(dp(36), WRAP))
        container.addView(row)

        val details = context.vertical().apply {
            background = rounded(Palette.GLASS, dp(14).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                context.text(
                    "${TimeText.dayName(day.date)}, ${TimeText.dayMonth(day.date)} · ${WeatherCodes.describe(day.weatherCode)}",
                    14f, Palette.TEXT, Fonts.medium,
                ).params(bottom = 4),
            )
            detailLine(this, "Yağış", "${AlertEngine.formatAmount(day.precipitationSum)} mm" +
                (day.precipitationProbabilityMax?.let { " · olasılık %$it" } ?: ""))
            if (day.snowfallSum > 0) detailLine(this, "Kar", "${AlertEngine.formatAmount(day.snowfallSum)} cm")
            detailLine(this, "Rüzgar", "${day.windSpeedMax.roundToInt()} km/sa · hamle ${day.windGustsMax.roundToInt()} km/sa")
            detailLine(this, "Hissedilen", "${day.apparentMin.deg()} / ${day.apparentMax.deg()}")
            day.uvIndexMax?.let { detailLine(this, "UV indeksi", "${it.roundToInt()} · ${uvLabel(it)}") }
            if (day.sunrise != null && day.sunset != null) {
                detailLine(this, "Gün doğumu / batımı", "${TimeText.hour(day.sunrise)} / ${TimeText.hour(day.sunset)}")
            }
        }
        container.addView(details.params(top = 10))
        details.visibility = if (expanded[key] == true) View.VISIBLE else View.GONE
        container.setOnClickListener {
            val open = expanded[key] != true
            expanded[key] = open
            details.visibility = if (open) View.VISIBLE else View.GONE
        }
        return container
    }

    private fun detailLine(parent: LinearLayout, label: String, value: String) {
        val row = context.horizontal()
        row.addView(context.text(label, 14f, Palette.TEXT_SECONDARY), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(context.text(value, 14f, Palette.TEXT))
        parent.addView(row.params(top = 4))
    }

    private class Tile(val icon: Int, val label: String, val value: String, val note: String, val rotation: Float = 0f)

    private fun detailsSection(current: CurrentWeather, hour: HourlyPoint?, today: DailyPoint?): View {
        val tiles = mutableListOf<Tile>()
        val feelsDiff = current.apparentTemperature - current.temperature
        tiles += Tile(
            R.drawable.ic_thermometer, "Hissedilen", current.apparentTemperature.deg(),
            when {
                feelsDiff <= -2 -> "Rüzgar nedeniyle daha soğuk"
                feelsDiff >= 2 -> "Nem nedeniyle daha sıcak"
                else -> "Gerçek sıcaklığa yakın"
            },
        )
        tiles += Tile(
            R.drawable.ic_droplet, "Nem", "%${current.humidity}",
            when {
                current.humidity < 30 -> "Kuru hava"
                current.humidity < 60 -> "Konforlu"
                current.humidity < 80 -> "Nemli"
                else -> "Çok nemli"
            },
        )
        tiles += Tile(
            R.drawable.ic_arrow_up, "Rüzgar · ${compass(current.windDirection)}", "${current.windSpeed.roundToInt()} km/sa",
            "Hamle ${current.windGusts.roundToInt()} km/sa",
            // The arrow points where the wind blows to.
            rotation = (current.windDirection + 180).toFloat(),
        )
        val uv = hour?.uvIndex
        tiles += if (uv != null) Tile(R.drawable.ic_sun, "UV indeksi", uv.roundToInt().toString(), uvLabel(uv))
        else Tile(R.drawable.ic_wind, "Hamle", "${current.windGusts.roundToInt()} km/sa", "Anlık en yüksek rüzgar")
        hour?.visibility?.let { visibility ->
            tiles += Tile(
                R.drawable.ic_eye, "Görüş",
                if (visibility >= 1000) "${AlertEngine.formatAmount(visibility / 1000)} km" else "${visibility.roundToInt()} m",
                when {
                    visibility < 1000 -> "Sisli, dikkatli sürün"
                    visibility < 5000 -> "Puslu"
                    else -> "Açık"
                },
            )
        }
        tiles += Tile(
            R.drawable.ic_gauge, "Basınç", "${current.pressure.roundToInt()} hPa",
            when {
                current.pressure < 1005 -> "Alçak basınç"
                current.pressure > 1022 -> "Yüksek basınç"
                else -> "Normal"
            },
        )
        if (today?.sunrise != null && today.sunset != null) {
            tiles += Tile(R.drawable.ic_sunrise, "Gün doğumu", TimeText.hour(today.sunrise), "Gün batımı ${TimeText.hour(today.sunset)}")
        }
        if (today != null) {
            tiles += Tile(
                R.drawable.ic_umbrella, "Bugün yağış", "${AlertEngine.formatAmount(today.precipitationSum)} mm",
                today.precipitationProbabilityMax?.let { "En yüksek olasılık %$it" } ?: "Günlük toplam",
            )
        }

        return context.vertical().apply {
            addView(context.sectionTitle(R.drawable.ic_info, "Ayrıntılar").params(start = 4, end = 4, bottom = 10))
            tiles.chunked(2).forEach { pair ->
                val row = context.horizontal().apply { gravity = Gravity.NO_GRAVITY }
                pair.forEachIndexed { index, tile ->
                    row.addView(detailTile(tile), LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (index == 0) marginEnd = dp(10) })
                }
                if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                addView(row.params(bottom = 10))
            }
        }
    }

    private fun detailTile(tile: Tile): View = context.vertical().apply {
        background = context.glassBackground()
        setPadding(dp(14), dp(14), dp(14), dp(14))
        val header = context.horizontal()
        header.addView(context.icon(tile.icon, Palette.TEXT_TERTIARY, 15).apply { rotation = tile.rotation })
        header.addView(context.space(widthDp = 6))
        header.addView(context.text(tile.label, 12f, Palette.TEXT_TERTIARY, Fonts.medium, maxLines = 1))
        addView(header)
        addView(context.text(tile.value, 22f, Palette.TEXT, Fonts.medium).params(top = 8))
        addView(context.text(tile.note, 12f, Palette.TEXT_SECONDARY, maxLines = 2).params(top = 3))
    }

    // endregion
    // region Full screen states ----------------------------------------------------------------

    private fun centered(): LinearLayout = context.vertical().apply { gravity = Gravity.CENTER }

    private fun loading(message: String): View = centered().apply {
        addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(context.text(message, 16f, Palette.TEXT_SECONDARY).params(WRAP, WRAP, top = 16))
    }

    private fun error(message: String?): View = centered().apply {
        addView(context.icon(R.drawable.ic_alert, Palette.TEXT_SECONDARY, 52))
        addView(context.text("Hava durumu alınamadı", 20f, Palette.TEXT, Fonts.medium).params(WRAP, WRAP, top = 16))
        addView(
            context.text(message ?: "İnternet bağlantınızı kontrol edip tekrar deneyin.", 14f, Palette.TEXT_SECONDARY)
                .apply { gravity = Gravity.CENTER }.params(WRAP, WRAP, top = 6),
        )
        addView(primaryButton("Tekrar dene", R.drawable.ic_refresh) { actions.refresh() }.params(MATCH, dp(52), top = 24))
        addView(secondaryButton("Başka bir yer ara", R.drawable.ic_search) { actions.openPlaces() }.params(MATCH, dp(52), top = 10))
    }

    private fun welcome(): View = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        val column = context.vertical().apply { gravity = Gravity.CENTER_VERTICAL }
        column.addView(WeatherIconView(context, 81, true), LinearLayout.LayoutParams(dp(112), dp(112)))
        column.addView(context.text("Hava Uyarı", 36f, Palette.TEXT, Fonts.bold).params(top = 12))
        column.addView(
            context.text(
                "Yağış, fırtına, don, sis ve aşırı sıcak gibi durumları önceden görün; ne zaman ve ne kadar ciddi " +
                    "olacağını, nasıl önlem alacağınızı öğrenin.",
                16f, Palette.TEXT_SECONDARY,
            ).params(top = 8),
        )
        val features = card()
        features.addView(feature("Renkli uyarı seviyeleri", "Sarı, turuncu, kırmızı — MGM'nin kullandığı ölçekle."))
        features.addView(feature("Saat saat yağış", "Yağmurun ne zaman başlayıp biteceğini grafikte görün.").params(top = 12))
        features.addView(feature("Her yeri arayın", "İstediğiniz şehir veya ilçenin hava durumuna bakın, kaydedin.").params(top = 12))
        column.addView(features.params(top = 24))
        val locate = primaryButton("Konumumu kullan", R.drawable.ic_crosshair) { actions.useLocation() }
        locateButton = locate
        locateLabel = locate.findViewWithTag(LABEL_TAG)
        column.addView(locate.params(MATCH, dp(52), top = 28))
        column.addView(secondaryButton("Şehir veya ilçe ara", R.drawable.ic_search) { actions.openPlaces() }.params(MATCH, dp(52), top = 10))
        addView(column, LayoutParams(MATCH, WRAP))
    }

    private fun outdatedCard(): View = card().apply {
        addView(context.icon(R.drawable.ic_alert, Palette.TEXT, 28))
        addView(context.text("Güncel tahmin yok", 18f, Palette.TEXT, Fonts.medium).params(top = 10))
        addView(
            context.text(
                "Bu yer için kayıtlı tahminin süresi doldu. İnternete bağlanıp yenileyin; eski verilerle uyarı gösterilmez.",
                14f, Palette.TEXT_SECONDARY,
            ).params(top = 4),
        )
        addView(primaryButton("Yenile", R.drawable.ic_refresh) { actions.refresh() }.params(MATCH, dp(48), top = 16))
    }

    private fun feature(title: String, text: String): View = context.horizontal().apply {
        gravity = Gravity.TOP
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SUN) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { topMargin = dp(6) })
        addView(context.space(widthDp = 12))
        val texts = context.vertical()
        texts.addView(context.text(title, 15f, Palette.TEXT, Fonts.medium))
        texts.addView(context.text(text, 14f, Palette.TEXT_SECONDARY).params(top = 2))
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun primaryButton(label: String, iconRes: Int, onClick: () -> Unit): View = context.horizontal().apply {
        gravity = Gravity.CENTER
        background = ripple(rounded(Color.WHITE, dp(26).toFloat()), radius = dp(26).toFloat())
        val dark = Color.parseColor("#0F2C63")
        addView(context.icon(iconRes, dark, 18))
        addView(context.space(widthDp = 10))
        addView(context.text(label, 16f, dark, Fonts.medium).apply { tag = LABEL_TAG })
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, iconRes: Int, onClick: () -> Unit): View = context.horizontal().apply {
        gravity = Gravity.CENTER
        background = ripple(rounded(Color.TRANSPARENT, dp(26).toFloat(), Color.argb(110, 255, 255, 255), dp(1)), radius = dp(26).toFloat())
        addView(context.icon(iconRes, Palette.TEXT, 18))
        addView(context.space(widthDp = 10))
        addView(context.text(label, 16f, Palette.TEXT, Fonts.medium))
        setOnClickListener { onClick() }
    }

    // endregion

    private companion object {
        const val LABEL_TAG = "label"
    }
}

fun uvLabel(uv: Double): String = when {
    uv < 3 -> "Düşük"
    uv < 6 -> "Orta"
    uv < 8 -> "Yüksek"
    uv < 11 -> "Çok yüksek"
    else -> "Aşırı"
}

private val COMPASS = listOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")

/** Turkish 8-point compass label for a meteorological direction (where the wind comes from). */
fun compass(degrees: Double): String = COMPASS[((((degrees % 360) + 360) % 360) / 45.0).roundToInt() % 8]

/** Severity of the most serious alert per day, for the dots in the daily list. */
fun alertDays(alerts: List<WeatherAlert>): Map<LocalDate, Severity> {
    val result = HashMap<LocalDate, Severity>()
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
