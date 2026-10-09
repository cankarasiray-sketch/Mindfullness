package com.mindfullness.weather.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import com.mindfullness.weather.R
import com.mindfullness.weather.domain.Severity

class SettingsView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun setAlertsEnabled(enabled: Boolean)
        fun setMinSeverity(severity: Severity)
        fun setMorningSummary(enabled: Boolean)
        fun setRainSoon(enabled: Boolean)
        fun useViewingPlaceForNotifications()
        fun openNotificationSettings()
        fun back()
    }

    private val scroll = ScrollView(context)
    private val body = context.vertical()
    private var insetBottom = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.BACKGROUND)
        val header = context.horizontal().apply { setPadding(dp(4), dp(4), dp(16), dp(4)) }
        header.addView(context.iconButton(R.drawable.ic_back, "Geri", Palette.ON_SURFACE) { actions.back() })
        header.addView(context.text("Ayarlar", 20f, Palette.ON_SURFACE, Fonts.medium).params(WRAP, WRAP, start = 4))
        addView(header, LayoutParams(MATCH, WRAP))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val left: Int
            val right: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                insetBottom = bars.bottom
                left = bars.left
                right = bars.right
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
            }
            setPadding(left, top, right, 0)
            body.setPadding(dp(16), dp(8), dp(16), insetBottom + dp(24))
            insets
        }
    }

    fun render(state: SettingsState) {
        val scrollY = scroll.scrollY
        body.removeAllViews()

        body.addView(sectionLabel("Bildirimler"))
        if (state.notificationsBlocked) body.addView(blockedWarning().params(bottom = 12))
        val notifications = section()
        notifications.addView(
            switchRow(
                "Hava uyarıları",
                "Önümüzdeki 24 saatte önemli bir hava olayı beklendiğinde bildirim alın. Tahmin düzenli olarak arka planda kontrol edilir.",
                state.alertsEnabled,
            ) { actions.setAlertsEnabled(it) },
        )
        if (state.alertsEnabled) {
            notifications.addView(divider())
            notifications.addView(
                context.text("Hangi seviyede bildirilsin?", 14f, Palette.ON_SURFACE_MUTED, Fonts.medium).params(start = 16, top = 14, bottom = 4),
            )
            notifications.addView(severityOption("Sarı ve üzeri (önerilen)", Severity.YELLOW, state.minSeverity))
            notifications.addView(severityOption("Yalnızca turuncu ve kırmızı", Severity.ORANGE, state.minSeverity))
            notifications.addView(divider())
            notifications.addView(
                switchRow(
                    "Yağmur başlamadan haber ver",
                    "Yağmur veya kar 1–2 saat içinde başlayacaksa kısa bir bildirim alın. Bunun için tahmin saatte bir kontrol edilir.",
                    state.rainSoonEnabled,
                ) { actions.setRainSoon(it) },
            )
            notifications.addView(divider())
            notifications.addView(notificationPlaceRow(state))
        }
        notifications.addView(divider())
        notifications.addView(
            switchRow(
                "Sabah özeti",
                "Her sabah 06:00 civarında günün hava durumu ve yanınıza almanız gerekenler.",
                state.morningSummaryEnabled,
            ) { actions.setMorningSummary(it) },
        )
        body.addView(notifications)

        body.addView(sectionLabel("Uyarı seviyeleri").params(top = 24))
        val levels = section()
        levels.addView(levelRow(Severity.INFO, "Bilgi", "Bilmeniz faydalı; örneğin hafif yağmur veya yüksek UV."))
        levels.addView(levelRow(Severity.YELLOW, "Sarı · Dikkatli olun", "Günlük işleri etkileyebilir; tedbirli olun."))
        levels.addView(levelRow(Severity.ORANGE, "Turuncu · Hazırlıklı olun", "Tehlikeli olabilir; planlarınızı gözden geçirin."))
        levels.addView(levelRow(Severity.RED, "Kırmızı · Önlem alın", "Çok tehlikeli; önlem alın ve resmî uyarıları izleyin."))
        body.addView(levels)

        body.addView(sectionLabel("Hakkında").params(top = 24))
        val about = section().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        about.addView(
            context.text(
                "Hava verileri Open-Meteo.com tarafından sağlanır (CC BY 4.0); uyarıların güven derecesi ECMWF, DWD ICON " +
                    "ve NOAA GFS modelleri karşılaştırılarak, hava kalitesi Copernicus CAMS verisiyle hesaplanır. Uyarılar " +
                    "otomatik değerlendirmelerdir; resmî uyarılar için MGM ve AFAD duyurularını takip edin.",
                14f, Palette.ON_SURFACE_MUTED,
            ),
        )
        if (state.version.isNotBlank()) {
            about.addView(context.text("Sürüm ${state.version}", 12f, Palette.ON_SURFACE_MUTED).params(top = 10))
        }
        body.addView(about)
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    /** Shown when notifications are on in the app but blocked by the system. */
    private fun blockedWarning(): View = context.horizontal().apply {
        background = ripple(rounded(Palette.WARNING_SURFACE, dp(16).toFloat()), radius = dp(16).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { actions.openNotificationSettings() }
        addView(context.icon(R.drawable.ic_alert, Palette.severity(Severity.ORANGE), 22))
        val texts = context.vertical()
        texts.addView(context.text("Bildirimler telefon ayarlarından kapalı", 15f, Palette.ON_SURFACE, Fonts.medium))
        texts.addView(context.text("Uyarılar gönderilemiyor. Açmak için dokunun.", 14f, Palette.ON_SURFACE_MUTED).params(top = 2))
        addView(texts, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
    }

    private fun sectionLabel(text: String): View =
        context.text(text, 14f, Palette.ACCENT, Fonts.medium).params(start = 4, bottom = 8)

    private fun section(): LinearLayout = context.vertical().apply {
        background = rounded(Palette.SURFACE, dp(20).toFloat())
        clipToOutline = true
    }

    private fun divider(): View = View(context).apply { setBackgroundColor(Palette.SURFACE_HIGH) }
        .params(MATCH, dp(1), start = 16, end = 16)

    private fun switchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val switch = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Palette.ACCENT, Palette.ON_SURFACE_MUTED),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Palette.withAlpha(Palette.ACCENT, 0.5f), Palette.OUTLINE),
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return context.horizontal().apply {
            background = ripple(null)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { switch.toggle() }
            val texts = context.vertical()
            texts.addView(context.text(title, 16f, Palette.ON_SURFACE, Fonts.medium))
            texts.addView(context.text(text, 14f, Palette.ON_SURFACE_MUTED).params(top = 3))
            addView(texts, LayoutParams(0, WRAP, 1f))
            addView(switch.params(WRAP, WRAP, start = 12))
        }
    }

    private fun severityOption(label: String, value: Severity, selected: Severity): View {
        val radio = RadioButton(context).apply {
            isChecked = value == selected
            isClickable = false
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Palette.ACCENT, Palette.ON_SURFACE_MUTED),
            )
        }
        return context.horizontal().apply {
            background = ripple(null)
            setPadding(dp(8), dp(2), dp(16), dp(2))
            minimumHeight = dp(48)
            setOnClickListener { if (value != selected) actions.setMinSeverity(value) }
            addView(radio)
            radio.jumpDrawablesToCurrentState()
            addView(context.text(label, 16f, Palette.ON_SURFACE).params(WRAP, WRAP, start = 4))
        }
    }

    private fun notificationPlaceRow(state: SettingsState): View = context.vertical().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        val row = context.horizontal()
        row.addView(context.icon(R.drawable.ic_pin, Palette.ON_SURFACE_MUTED, 22))
        val texts = context.vertical()
        texts.addView(context.text("Bildirim konumu", 16f, Palette.ON_SURFACE, Fonts.medium))
        val place = state.notificationPlace
        texts.addView(
            context.text(
                place?.let { if (it.isCurrentLocation) "${it.name} (mevcut konum)" else it.name } ?: "Seçilmedi",
                14f, Palette.ON_SURFACE_MUTED,
            ).params(top = 2),
        )
        texts.addView(context.text("Ana ekran widget'ı da bu konumu gösterir.", 12f, Palette.ON_SURFACE_MUTED).params(top = 4))
        row.addView(texts, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        addView(row)
        val viewing = state.viewingPlace
        if (viewing != null && viewing.id != place?.id) {
            addView(
                context.text("${viewing.name} için bildirim al", 14f, Palette.ACCENT, Fonts.medium).apply {
                    background = ripple(null, radius = dp(18).toFloat())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { actions.useViewingPlaceForNotifications() }
                }.params(WRAP, WRAP, start = 24, top = 6),
            )
        }
    }

    private fun levelRow(severity: Severity, title: String, text: String): View = context.horizontal().apply {
        gravity = Gravity.TOP
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.severity(severity)) }
        }, LayoutParams(dp(12), dp(12)).apply { topMargin = dp(4) })
        val texts = context.vertical()
        texts.addView(context.text(title, 15f, Palette.ON_SURFACE, Fonts.medium))
        texts.addView(context.text(text, 14f, Palette.ON_SURFACE_MUTED).params(top = 2))
        addView(texts, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
    }
}
