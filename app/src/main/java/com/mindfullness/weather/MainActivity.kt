package com.mindfullness.weather

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.platform.Async
import com.mindfullness.weather.platform.Notifier
import com.mindfullness.weather.ui.AppController
import com.mindfullness.weather.ui.HomeView
import com.mindfullness.weather.ui.Palette
import com.mindfullness.weather.ui.PlacesView
import com.mindfullness.weather.ui.SettingsView

class MainActivity : Activity() {
    private enum class Screen { HOME, PLACES, SETTINGS }

    private lateinit var controller: AppController
    private lateinit var root: FrameLayout
    private lateinit var homeView: HomeView
    private lateinit var placesView: PlacesView
    private lateinit var settingsView: SettingsView
    private var screen = Screen.HOME
    private var pendingNotificationAction: (() -> Unit)? = null
    private var permissionRequestInFlight = false
    private var renderPosted = false

    // Kept in fields so onDestroy only clears callbacks that still belong to this instance.
    private val renderCallback: () -> Unit = { scheduleRender() }
    private val messageCallback: (String) -> Unit = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as HavaUyariApp).controller
        drawEdgeToEdge()

        root = FrameLayout(this).apply { setBackgroundColor(Palette.BACKGROUND) }
        setContentView(root)
        homeView = HomeView(this, homeActions)
        placesView = PlacesView(this, placesActions)
        settingsView = SettingsView(this, settingsActions)

        controller.onChange = renderCallback
        controller.onMessage = messageCallback
        val restored = savedInstanceState?.getString(KEY_SCREEN)?.let { name -> Screen.values().firstOrNull { it.name == name } }
        show(restored ?: Screen.HOME, animate = false)
        controller.start()
        if (savedInstanceState == null) openNotificationTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotificationTarget(intent)
    }

    /** A tapped weather notification opens the home screen for the place it was about. */
    private fun openNotificationTarget(intent: Intent?) {
        val placeId = intent?.takeIf { it.hasExtra(Notifier.EXTRA_PLACE_ID) }?.getLongExtra(Notifier.EXTRA_PLACE_ID, 0) ?: return
        intent.removeExtra(Notifier.EXTRA_PLACE_ID)
        if (screen != Screen.HOME) goHome()
        controller.openPlace(placeId)
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
        // Notification permission may have changed in system settings while we were away.
        render()
    }

    override fun onDestroy() {
        if (controller.onChange === renderCallback) controller.onChange = null
        if (controller.onMessage === messageCallback) controller.onMessage = null
        Async.main.removeCallbacks(renderRunnable)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != Screen.HOME) {
            goHome()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun drawEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun show(target: Screen, animate: Boolean = true) {
        screen = target
        val view: View = when (target) {
            Screen.HOME -> homeView
            Screen.PLACES -> placesView
            Screen.SETTINGS -> settingsView
        }
        if (view.parent !== root) {
            root.removeAllViews()
            root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            view.requestApplyInsets()
            if (animate) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(180).start()
            }
        }
        if (target == Screen.PLACES) {
            // A fresh screen starts with an empty search box, so the controller's query must match.
            controller.clearQuery()
            controller.refreshSummaries()
            placesView.onShown(hasSavedPlaces = controller.places.saved.isNotEmpty())
        }
        render()
    }

    private fun goHome() {
        if (screen == Screen.PLACES) {
            placesView.hideKeyboard()
            controller.clearQuery()
        }
        show(Screen.HOME)
    }

    private val renderRunnable = Runnable {
        renderPosted = false
        render()
    }

    /** Coalesces bursts of state changes (e.g. several place summaries arriving) into one render. */
    private fun scheduleRender() {
        if (!renderPosted) {
            renderPosted = true
            Async.main.post(renderRunnable)
        }
    }

    private fun render() {
        when (screen) {
            Screen.HOME -> homeView.render(controller.home)
            Screen.PLACES -> placesView.render(controller.places)
            Screen.SETTINGS -> settingsView.render(controller.settings)
        }
    }

    // region Permissions ----------------------------------------------------------------------

    private fun requestLocation() {
        if (controller.hasLocationPermission()) {
            controller.useCurrentLocation()
        } else if (!permissionRequestInFlight) {
            permissionRequestInFlight = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
        }
    }

    /** Runs [action] once notifications are allowed (Android 13+ asks at runtime). */
    private fun withNotificationPermission(action: () -> Unit) {
        when {
            !Notifier.hasPermission(this) -> {
                if (permissionRequestInFlight) return
                permissionRequestInFlight = true
                pendingNotificationAction = action
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
            !Notifier.canNotify(this) -> {
                // The permission is there but the user switched the app's notifications off.
                controller.message("Bildirimler telefon ayarlarından kapalı. Açmak için ayarlar açılıyor.")
                openNotificationSettings()
            }
            else -> action()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequestInFlight = false
        // An empty result means the request was interrupted, not that the user said no.
        if (grantResults.isEmpty()) return
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQUEST_LOCATION -> if (granted) controller.useCurrentLocation() else controller.onLocationDenied()
            REQUEST_NOTIFICATIONS -> {
                val action = pendingNotificationAction
                pendingNotificationAction = null
                if (granted) action?.let(::withNotificationPermission)
                else controller.message("Bildirim izni verilmedi. İzni telefon ayarlarından açabilirsiniz.")
                render()
            }
        }
    }

    // endregion
    // region Screen actions -------------------------------------------------------------------

    private val homeActions = object : HomeView.Actions {
        override fun refresh() = controller.refresh()
        override fun openPlaces() = show(Screen.PLACES)
        override fun openSettings() = show(Screen.SETTINGS)
        override fun useLocation() = requestLocation()
    }

    private val placesActions = object : PlacesView.Actions {
        override fun onQueryChange(text: String) = controller.onQueryChange(text)

        override fun select(place: Place) {
            controller.select(place)
            goHome()
        }

        override fun selectCurrentLocation() {
            requestLocation()
            goHome()
        }

        override fun remove(place: Place) = controller.remove(place)

        override fun setNotificationPlace(place: Place) = withNotificationPermission { controller.setNotificationPlace(place) }

        override fun back() = goHome()
    }

    private val settingsActions = object : SettingsView.Actions {
        override fun setAlertsEnabled(enabled: Boolean) {
            if (enabled) withNotificationPermission { controller.setAlertsEnabled(true) } else controller.setAlertsEnabled(false)
            render()
        }

        override fun setMinSeverity(severity: Severity) = controller.setMinSeverity(severity)

        override fun setMorningSummary(enabled: Boolean) {
            if (enabled) withNotificationPermission { controller.setMorningSummary(true) } else controller.setMorningSummary(false)
            render()
        }

        override fun useViewingPlaceForNotifications() = withNotificationPermission { controller.useViewingPlaceForNotifications() }

        override fun openNotificationSettings() = this@MainActivity.openNotificationSettings()

        override fun back() = goHome()
    }

    // endregion

    private companion object {
        const val KEY_SCREEN = "screen"
        const val REQUEST_LOCATION = 1
        const val REQUEST_NOTIFICATIONS = 2
    }
}
