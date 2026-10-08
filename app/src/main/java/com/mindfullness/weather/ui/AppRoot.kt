package com.mindfullness.weather.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.ui.home.HomeScreen
import com.mindfullness.weather.ui.places.PlacesScreen
import com.mindfullness.weather.ui.settings.SettingsScreen

private enum class Screen { HOME, PLACES, SETTINGS }

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val home by viewModel.home.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    BackHandler(enabled = screen != Screen.HOME) {
        viewModel.onQueryChange("")
        screen = Screen.HOME
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.useCurrentLocation() else viewModel.onLocationDenied()
    }
    val requestLocation: () -> Unit = {
        if (viewModel.hasLocationPermission()) {
            viewModel.useCurrentLocation()
        } else {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Runs the action once notifications are allowed (Android 13+ asks at runtime).
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingNotificationAction?.invoke()
        else viewModel.message("Bildirim izni verilmedi. İzni telefon ayarlarından açabilirsiniz.")
        pendingNotificationAction = null
    }
    val withNotificationPermission: (() -> Unit) -> Unit = { action ->
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingNotificationAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.HOME -> HomeScreen(
                    state = home,
                    onRefresh = viewModel::refresh,
                    onOpenPlaces = {
                        viewModel.refreshSummaries()
                        screen = Screen.PLACES
                    },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onUseLocation = requestLocation,
                )
                Screen.PLACES -> PlacesScreen(
                    state = places,
                    onQueryChange = viewModel::onQueryChange,
                    onSelect = { place ->
                        viewModel.select(place)
                        screen = Screen.HOME
                    },
                    onSelectCurrentLocation = {
                        requestLocation()
                        screen = Screen.HOME
                    },
                    onRemove = viewModel::remove,
                    onSetNotificationPlace = { place: Place ->
                        withNotificationPermission { viewModel.setNotificationPlace(place) }
                    },
                    onBack = {
                        viewModel.onQueryChange("")
                        screen = Screen.HOME
                    },
                )
                Screen.SETTINGS -> SettingsScreen(
                    state = settings,
                    onAlertsChange = { enabled ->
                        if (enabled) withNotificationPermission { viewModel.setAlertsEnabled(true) }
                        else viewModel.setAlertsEnabled(false)
                    },
                    onMinSeverityChange = viewModel::setMinSeverity,
                    onMorningSummaryChange = { enabled ->
                        if (enabled) withNotificationPermission { viewModel.setMorningSummary(true) }
                        else viewModel.setMorningSummary(false)
                    },
                    onUseViewingPlaceForNotifications = viewModel::useViewingPlaceForNotifications,
                    onBack = { screen = Screen.HOME },
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp),
        )
    }
}
