package com.mindfullness.weather

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.platform.CrashReport
import com.mindfullness.weather.ui.Fonts
import com.mindfullness.weather.ui.MATCH
import com.mindfullness.weather.ui.Palette
import com.mindfullness.weather.ui.WRAP
import com.mindfullness.weather.ui.dp
import com.mindfullness.weather.ui.horizontal
import com.mindfullness.weather.ui.icon
import com.mindfullness.weather.ui.params
import com.mindfullness.weather.ui.ripple
import com.mindfullness.weather.ui.rounded
import com.mindfullness.weather.ui.text
import com.mindfullness.weather.ui.vertical

/**
 * Shown on the start after a crash, before the main screen is built, so the report can be shared
 * even if the app crashes again right after opening.
 */
class CrashReportActivity : Activity() {
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = CrashReport.pending(this) ?: run {
            continueToApp()
            return
        }
        val column = vertical().apply {
            setBackgroundColor(Palette.BACKGROUND)
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        column.addView(icon(R.drawable.ic_alert, Palette.severity(Severity.ORANGE), 40))
        column.addView(text("Uygulama beklenmedik şekilde kapandı", 22f, Palette.ON_SURFACE, Fonts.medium).params(top = 16))
        column.addView(
            text(
                "Sorunun çözülebilmesi için aşağıdaki hata raporunu uygulamayı size gönderen kişiyle paylaşın. " +
                    "Raporda kişisel bilgi yoktur; yalnızca cihaz modeli, Android sürümü ve hatanın yeri bulunur.",
                15f, Palette.ON_SURFACE_MUTED,
            ).params(top = 8),
        )
        val preview = ScrollView(this).apply {
            background = rounded(Palette.SURFACE, dp(14).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(text(report, 11f, Palette.ON_SURFACE_MUTED, Typeface.MONOSPACE))
        }
        column.addView(preview, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
        column.addView(button("Raporu paylaş", primary = true) { share() }.params(MATCH, dp(52), top = 16))
        column.addView(button("Uygulamaya dön", primary = false) { continueToApp() }.params(MATCH, dp(52), top = 10))
        setContentView(column)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = continueToApp()

    private fun share() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Hava Uyarı hata raporu")
            .putExtra(Intent.EXTRA_TEXT, report)
        try {
            startActivity(Intent.createChooser(send, "Hata raporunu paylaş"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Paylaşılacak bir uygulama bulunamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun continueToApp() {
        CrashReport.clear(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = horizontal().apply {
        gravity = Gravity.CENTER
        val radius = dp(26).toFloat()
        background = ripple(
            if (primary) rounded(Palette.ACCENT, radius) else rounded(Palette.SURFACE_HIGH, radius),
            radius = radius,
        )
        addView(text(label, 16f, if (primary) Palette.BACKGROUND else Palette.ON_SURFACE, Fonts.medium).params(WRAP, WRAP))
        setOnClickListener { onClick() }
    }
}
