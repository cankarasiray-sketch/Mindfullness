package com.mindfullness.weather.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import com.mindfullness.weather.R
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import java.util.Locale

class PlacesView(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun onQueryChange(text: String)
        fun select(place: Place)
        fun selectCurrentLocation()
        fun remove(place: Place)
        fun setNotificationPlace(place: Place)
        fun back()
    }

    private val field = EditText(context)
    private val clearButton = context.iconButton(R.drawable.ic_close, "Temizle", Palette.ON_SURFACE_MUTED) { field.setText("") }
    private val scroll = ScrollView(context)
    private val list = context.vertical()
    private var insetTop = 0
    private var insetBottom = 0
    private var touching = false
    private var pendingState: PlacesState? = null
    private var renderedQuery: String? = null
    private var renderedResults: List<Place>? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.BACKGROUND)

        val header = context.horizontal().apply { setPadding(dp(4), dp(8), dp(12), dp(8)) }
        header.addView(context.iconButton(R.drawable.ic_back, "Geri", Palette.ON_SURFACE) { actions.back() })
        val fieldBox = FrameLayout(context).apply { background = rounded(Palette.SURFACE_HIGH, dp(28).toFloat()) }
        field.apply {
            hint = "Şehir, ilçe veya yer ara"
            setHintTextColor(Palette.ON_SURFACE_MUTED)
            setTextColor(Palette.ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(48), dp(14), dp(44), dp(14))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable?.setTint(Palette.ACCENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    actions.onQueryChange(text)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        fieldBox.addView(field, FrameLayout.LayoutParams(MATCH, WRAP))
        fieldBox.addView(
            context.icon(R.drawable.ic_search, Palette.ON_SURFACE_MUTED, 20)
                .frameParams(dp(20), dp(20), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { (layoutParams as FrameLayout.LayoutParams).marginStart = dp(16) },
        )
        clearButton.visibility = View.GONE
        fieldBox.addView(clearButton.frameParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        header.addView(fieldBox, LayoutParams(0, WRAP, 1f))
        addView(header, LayoutParams(MATCH, WRAP))

        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        setOnApplyWindowInsetsListener { _, insets ->
            val left: Int
            val right: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = bars.top
                insetBottom = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
                left = bars.left
                right = bars.right
            } else {
                @Suppress("DEPRECATION")
                insetTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
            }
            setPadding(left, insetTop, right, 0)
            list.setPadding(dp(16), dp(4), dp(16), insetBottom + dp(24))
            insets
        }
    }

    /** Called when the screen becomes visible. */
    fun onShown(hasSavedPlaces: Boolean) {
        if (field.text.isNotEmpty()) field.setText("")
        scroll.scrollTo(0, 0)
        if (!hasSavedPlaces) {
            field.requestFocus()
            field.post {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    /** Rebuilding rows mid-tap would cancel the tap, so updates wait until the finger lifts. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                pendingState?.let { state -> post { if (!touching) render(state) } }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    fun render(state: PlacesState) {
        if (touching) {
            pendingState = state
            return
        }
        pendingState = null
        // Keep the scroll position only while the same list is being refreshed.
        val sameList = state.query == renderedQuery && state.results == renderedResults
        renderedQuery = state.query
        renderedResults = state.results
        val scrollY = if (sameList) scroll.scrollY else 0
        list.removeAllViews()
        if (state.query.isBlank()) {
            list.addView(currentLocationRow(state).params(top = 4, bottom = 8))
            list.addView(header("Kayıtlı yerler"))
            if (state.saved.isEmpty()) {
                list.addView(
                    hint(
                        R.drawable.ic_pin,
                        "Henüz kayıtlı yer yok",
                        "Yukarıdan bir şehir veya ilçe arayın. Baktığınız yerler burada listelenir; tek dokunuşla geçiş yapabilirsiniz.",
                    ),
                )
            }
            state.saved.forEach { summary ->
                list.addView(
                    savedRow(summary, summary.place.id == state.selectedId, summary.place.id == state.notificationPlaceId)
                        .params(bottom = 8),
                )
            }
        } else {
            when {
                state.isSearching && state.results.isEmpty() -> list.addView(
                    ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT) },
                    LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(32) },
                )
                state.searchError != null -> list.addView(hint(R.drawable.ic_alert, "Arama yapılamadı", state.searchError))
                state.results.isEmpty() && state.query.trim().length >= 2 -> list.addView(
                    hint(R.drawable.ic_search, "Sonuç bulunamadı", "Yazımı kontrol edin veya daha genel bir yer adı deneyin."),
                )
            }
            state.results.forEach { place -> list.addView(resultRow(place)) }
        }
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun header(text: String): View =
        context.text(text, 14f, Palette.ON_SURFACE_MUTED, Fonts.medium).params(start = 4, top = 12, bottom = 10)

    private fun hint(icon: Int, title: String, text: String): View = context.vertical().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(32), dp(24), dp(32))
        addView(context.icon(icon, Palette.ON_SURFACE_MUTED, 40))
        addView(context.text(title, 16f, Palette.ON_SURFACE, Fonts.medium).params(WRAP, WRAP, top = 12))
        addView(context.text(text, 14f, Palette.ON_SURFACE_MUTED).apply { gravity = Gravity.CENTER }.params(WRAP, WRAP, top = 4))
    }

    private fun rowContainer(selected: Boolean, onClick: () -> Unit): LinearLayout = context.horizontal().apply {
        val shape = rounded(Palette.SURFACE, dp(20).toFloat(), if (selected) Palette.withAlpha(Palette.ACCENT, 0.6f) else Palette.SURFACE_HIGH, dp(1))
        background = ripple(shape, radius = dp(20).toFloat())
        setPadding(dp(16), dp(12), dp(4), dp(12))
        minimumHeight = dp(72)
        setOnClickListener { onClick() }
    }

    private fun circle(color: Int, sizeDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(sizeDp), dp(sizeDp))
    }

    private fun currentLocationRow(state: PlacesState): View =
        rowContainer(state.selectedId == Place.CURRENT_LOCATION_ID) { actions.selectCurrentLocation() }.apply {
            val badge = FrameLayout(context).apply { background = circle(Palette.withAlpha(Palette.ACCENT, 0.16f), 44) }
            badge.addView(context.icon(R.drawable.ic_navigation, Palette.ACCENT, 20).frameParams(dp(20), dp(20), Gravity.CENTER))
            addView(badge, LayoutParams(dp(44), dp(44)))
            addView(context.space(widthDp = 14))
            val texts = context.vertical()
            texts.addView(context.text("Mevcut konum", 16f, Palette.ON_SURFACE, Fonts.medium))
            texts.addView(
                context.text(
                    when {
                        state.isLocating -> "Konum bulunuyor…"
                        state.current != null -> state.current.place.name
                        else -> "Bulunduğunuz yerin hava durumu"
                    },
                    14f, Palette.ON_SURFACE_MUTED, maxLines = 1,
                ).params(top = 2),
            )
            addView(texts, LayoutParams(0, WRAP, 1f))
            if (state.isLocating) {
                addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT) }, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            } else {
                state.current?.let { weatherBadge(it)?.let { badgeView -> addView(badgeView.params(WRAP, WRAP, end = 14)) } }
            }
        }

    private fun savedRow(summary: PlaceSummary, selected: Boolean, notifies: Boolean): View =
        rowContainer(selected) { actions.select(summary.place) }.apply {
            val texts = context.vertical()
            val name = context.text(summary.place.name, 16f, Palette.ON_SURFACE, Fonts.medium, maxLines = 1)
            if (notifies) {
                // As part of the text, the bell stays visible when a long name is ellipsized.
                val bell = context.getDrawable(R.drawable.ic_bell)?.mutate()?.apply {
                    setTint(Palette.ACCENT)
                    setBounds(0, 0, dp(15), dp(15))
                }
                name.setCompoundDrawables(null, null, bell, null)
                name.compoundDrawablePadding = dp(6)
                name.contentDescription = "${summary.place.name}, bildirim konumu"
            }
            texts.addView(name.params(WRAP, WRAP))
            if (summary.place.subtitle.isNotBlank()) {
                texts.addView(context.text(summary.place.subtitle, 12f, Palette.ON_SURFACE_MUTED, maxLines = 1).params(top = 2))
            }
            summary.topAlert?.takeIf { it.severity >= Severity.YELLOW }?.let { alert ->
                texts.addView(
                    context.pill(alert.title, Palette.severity(alert.severity), Palette.ON_SEVERITY).params(WRAP, WRAP, top = 6),
                )
            }
            addView(texts, LayoutParams(0, WRAP, 1f))
            weatherBadge(summary)?.let { addView(it.params(WRAP, WRAP, start = 8)) }
            val more = context.iconButton(R.drawable.ic_more, "Seçenekler", Palette.ON_SURFACE_MUTED) {}
            more.setOnClickListener { showMenu(more, summary.place, notifies) }
            addView(more)
        }

    private fun showMenu(anchor: View, place: Place, notifies: Boolean) {
        val menu = PopupMenu(context, anchor)
        if (!notifies) menu.menu.add(0, 1, 0, "Bildirimleri bu yer için al")
        menu.menu.add(0, 2, 1, "Listeden kaldır")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> actions.setNotificationPlace(place)
                2 -> actions.remove(place)
            }
            true
        }
        menu.show()
    }

    private fun weatherBadge(summary: PlaceSummary): View? {
        val temperature = summary.temperature ?: return null
        val code = summary.weatherCode ?: return null
        return context.horizontal().apply {
            addView(WeatherIconView(context, code, summary.isDay), LayoutParams(dp(34), dp(34)))
            val column = context.vertical().apply { gravity = Gravity.END }
            column.addView(context.text(temperature.deg(), 20f, Palette.ON_SURFACE, Fonts.medium).params(WRAP, WRAP))
            if (summary.high != null && summary.low != null) {
                column.addView(context.text("${summary.high.deg()} / ${summary.low.deg()}", 11f, Palette.ON_SURFACE_MUTED).params(WRAP, WRAP, top = 2))
            }
            addView(column.params(WRAP, WRAP, start = 6))
        }
    }

    private fun resultRow(place: Place): View = context.horizontal().apply {
        background = ripple(null, radius = dp(20).toFloat())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener {
            hideKeyboard()
            actions.select(place)
        }
        val badge = FrameLayout(context).apply { background = circle(Palette.SURFACE_HIGH, 40) }
        val flag = place.countryCode?.let(::flagEmoji)
        if (flag != null) {
            badge.addView(context.text(flag, 18f).frameParams(WRAP, WRAP, Gravity.CENTER))
        } else {
            badge.addView(context.icon(R.drawable.ic_pin, Palette.ON_SURFACE_MUTED, 20).frameParams(dp(20), dp(20), Gravity.CENTER))
        }
        addView(badge, LayoutParams(dp(40), dp(40)))
        addView(context.space(widthDp = 14))
        val texts = context.vertical()
        texts.addView(context.text(place.name, 16f, Palette.ON_SURFACE, Fonts.medium))
        if (place.subtitle.isNotBlank()) texts.addView(context.text(place.subtitle, 12f, Palette.ON_SURFACE_MUTED, maxLines = 1).params(top = 2))
        addView(texts, LayoutParams(0, WRAP, 1f))
    }
}

/** Two-letter country code to its flag emoji, e.g. "TR" -> 🇹🇷. */
fun flagEmoji(countryCode: String): String? {
    val code = countryCode.uppercase(Locale.ROOT)
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
