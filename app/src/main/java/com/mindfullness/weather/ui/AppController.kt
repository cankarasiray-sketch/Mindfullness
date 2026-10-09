package com.mindfullness.weather.ui

import android.content.Context
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Insights
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.PrecipitationSummary
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.Tip
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.platform.AppGraph
import com.mindfullness.weather.platform.Async
import com.mindfullness.weather.platform.DeviceLocationProvider
import com.mindfullness.weather.platform.JobScheduling
import com.mindfullness.weather.platform.Notifier
import com.mindfullness.weather.platform.SettingsStore
import com.mindfullness.weather.platform.WeatherWidget
import org.threeten.bp.LocalDateTime
import kotlin.math.cos
import kotlin.math.sqrt

data class HomeState(
    val place: Place? = null,
    val content: ForecastContent? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLocating: Boolean = false,
    /** Set when the last refresh failed; the screen then shows cached data if it has any. */
    val errorMessage: String? = null,
    val needsOnboarding: Boolean = false,
)

/** Everything derived from one forecast, computed once per update. */
class ForecastContent(
    val forecast: Forecast,
    val now: LocalDateTime,
    val alerts: List<WeatherAlert>,
    val tips: List<Tip>,
    val precipitation: PrecipitationSummary,
) {
    val isStale: Boolean get() = System.currentTimeMillis() - forecast.fetchedAtMillis > STALE_AFTER_MILLIS

    companion object {
        const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L

        fun from(forecast: Forecast, nowMillis: Long = System.currentTimeMillis()): ForecastContent {
            val now = forecast.localNow(nowMillis)
            return ForecastContent(
                forecast = forecast,
                now = now,
                alerts = AlertEngine.evaluate(forecast, now),
                tips = Insights.tips(forecast, now),
                precipitation = Insights.precipitation(forecast, now),
            )
        }
    }
}

data class PlaceSummary(
    val place: Place,
    val temperature: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean = true,
    val high: Double? = null,
    val low: Double? = null,
    val topAlert: WeatherAlert? = null,
)

data class PlacesState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Place> = emptyList(),
    val searchError: String? = null,
    val current: PlaceSummary? = null,
    val isLocating: Boolean = false,
    val saved: List<PlaceSummary> = emptyList(),
    val selectedId: Long? = null,
    val notificationPlaceId: Long? = null,
)

data class SettingsState(
    val alertsEnabled: Boolean = false,
    val minSeverity: Severity = Severity.YELLOW,
    val morningSummaryEnabled: Boolean = false,
    val rainSoonEnabled: Boolean = true,
    val notificationPlace: Place? = null,
    val viewingPlace: Place? = null,
    val version: String = "",
    /** Notifications are wanted but blocked by the system (permission revoked or switched off). */
    val notificationsBlocked: Boolean = false,
)

/**
 * Holds the app state and performs every user action. Lives as long as the process, so state
 * survives activity re-creation. All methods must be called on the main thread.
 */
class AppController(context: Context) {
    private val app = context.applicationContext
    private val store = SettingsStore(app)
    private val repository = AppGraph.repository(app)
    private val locator = DeviceLocationProvider(app)

    @Suppress("DEPRECATION")
    private val versionName: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    var home = HomeState()
        private set

    var onChange: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    private var currentPlace: Place? = store.lastCurrentPlace
    private val summaries = HashMap<Long, PlaceSummary>()
    private var query = ""
    private var searchResults: List<Place> = emptyList()
    private var isSearching = false
    private var searchError: String? = null
    private var isLocating = false
    private var started = false
    private var fetchPending = false
    private val summariesInFlight = HashSet<Long>()

    private var loadToken = 0
    private var locateToken = 0
    private var searchToken = 0
    private val searchRunnable = Runnable { runSearch() }

    val places: PlacesState
        get() {
            val settings = store.settings
            return PlacesState(
                query = query,
                isSearching = isSearching,
                results = searchResults,
                searchError = searchError,
                current = currentPlace?.let { summaries[Place.CURRENT_LOCATION_ID]?.copy(place = it) ?: PlaceSummary(it) },
                isLocating = isLocating,
                saved = store.places.map { summaries[it.id] ?: PlaceSummary(it) },
                selectedId = home.place?.id,
                notificationPlaceId = settings.notificationPlace?.id.takeIf { settings.alertsEnabled },
            )
        }

    val settings: SettingsState
        get() {
            val settings = store.settings
            return SettingsState(
                alertsEnabled = settings.alertsEnabled,
                minSeverity = settings.minSeverity,
                morningSummaryEnabled = settings.morningSummary,
                rainSoonEnabled = settings.rainSoon,
                notificationPlace = settings.notificationPlace,
                viewingPlace = home.place,
                version = versionName,
                notificationsBlocked = (settings.alertsEnabled || settings.morningSummary) && !Notifier.canNotify(app),
            )
        }

    fun start() {
        if (started) return
        started = true
        restoreSelection()
    }

    fun hasLocationPermission() = locator.hasPermission()

    /** Shows the place a tapped notification was about. */
    fun openPlace(placeId: Long) {
        if (home.place?.id == placeId && home.content != null) return
        if (placeId == Place.CURRENT_LOCATION_ID) {
            val current = currentPlace
            when {
                locator.hasPermission() -> useCurrentLocation()
                current != null -> show(current)
            }
            return
        }
        val place = store.places.firstOrNull { it.id == placeId }
            ?: store.settings.notificationPlace?.takeIf { it.id == placeId }
            ?: return
        select(place)
    }

    // region Home ------------------------------------------------------------------------------

    private fun restoreSelection() {
        val selected = store.selectedId
        val saved = store.places
        val savedPlace = saved.firstOrNull { it.id == selected }
        val current = currentPlace
        when {
            selected == Place.CURRENT_LOCATION_ID && locator.hasPermission() -> useCurrentLocation()
            selected == Place.CURRENT_LOCATION_ID && current != null -> show(current)
            savedPlace != null -> show(savedPlace)
            saved.isNotEmpty() -> select(saved.first())
            else -> setHome(HomeState(needsOnboarding = true))
        }
    }

    fun select(place: Place) {
        if (place.isCurrentLocation) {
            useCurrentLocation()
            return
        }
        cancelLocating()
        store.addPlace(place)
        store.selectedId = place.id
        clearQuery()
        show(place)
    }

    fun useCurrentLocation(userRefresh: Boolean = false) {
        val token = ++locateToken
        // Results of fetches started for the previously shown place must not replace this view.
        loadToken++
        isLocating = true
        val lastKnown = currentPlace
        // Show the last known location's cached forecast while the new fix comes in.
        if (lastKnown != null && home.place?.isCurrentLocation != true) {
            Async.run({ repository.cached(lastKnown) }) { result ->
                val cached = result.getOrNull()
                if (cached != null && token == locateToken && isLocating && home.place?.isCurrentLocation != true) {
                    setHome(HomeState(place = lastKnown, content = ForecastContent.from(cached), isLocating = true, isRefreshing = true))
                }
            }
        }
        setHome(home.copy(isLocating = true, isRefreshing = home.content != null))

        locator.locate { located ->
            if (token != locateToken) return@locate
            isLocating = false
            val place = located?.let(::keepKnownName)
            if (place != null) {
                currentPlace = place
                store.lastCurrentPlace = place
                store.selectedId = Place.CURRENT_LOCATION_ID
                // Keep background alerts following the device when they are tied to "current location".
                if (store.settings.notificationPlace?.isCurrentLocation == true) {
                    store.update { it.copy(notificationPlace = place) }
                }
                setHome(home.copy(isLocating = false))
                show(place, userRefresh)
                return@locate
            }
            val fallback = currentPlace
            if (fallback != null) {
                message("Konum alınamadı; son bilinen konum gösteriliyor.")
                store.selectedId = Place.CURRENT_LOCATION_ID
                setHome(home.copy(isLocating = false))
                show(fallback, userRefresh)
                return@locate
            }
            message("Konum alınamadı. Konum servislerini açın veya bir şehir arayın.")
            // Stay on (or go back to) a real place; the selection only switches after a successful fix.
            val previous = home.place?.takeIf { !it.isCurrentLocation && home.content != null }
            val saved = store.places.firstOrNull()
            when {
                previous != null -> {
                    store.selectedId = previous.id
                    setHome(home.copy(isLocating = false, isRefreshing = false))
                }
                saved != null -> select(saved)
                else -> setHome(HomeState(needsOnboarding = true))
            }
        }
    }

    /** Offline geocoding yields a generic name; keep the district name of a nearby earlier fix. */
    private fun keepKnownName(place: Place): Place {
        val previous = currentPlace ?: return place
        if (place.name != DeviceLocationProvider.GENERIC_NAME || previous.name == DeviceLocationProvider.GENERIC_NAME) return place
        if (distanceKm(place, previous) > 5) return place
        return place.copy(name = previous.name, region = previous.region, country = previous.country, countryCode = previous.countryCode)
    }

    private fun cancelLocating() {
        locateToken++
        if (isLocating) {
            isLocating = false
            setHome(home.copy(isLocating = false))
        }
    }

    fun onLocationDenied() {
        message("Konum izni verilmedi. Dilediğiniz yeri arayarak hava durumuna bakabilirsiniz.")
    }

    fun refresh() {
        val place = home.place ?: return
        if (place.isCurrentLocation && locator.hasPermission()) useCurrentLocation(userRefresh = true)
        else show(place, userRefresh = true)
    }

    /** Re-derives time-based content and refetches when the data is older than 30 minutes. */
    fun onResume() {
        val state = home
        val content = state.content ?: return
        setHome(state.copy(content = ForecastContent.from(content.forecast)))
        val age = System.currentTimeMillis() - content.forecast.fetchedAtMillis
        if (age > 30 * 60_000L && !state.isLoading && !state.isRefreshing && !state.isLocating) {
            val place = state.place ?: return
            // The device may have moved since the last fix, so a stale current location is looked up again.
            if (place.isCurrentLocation && locator.hasPermission()) useCurrentLocation() else show(place)
        }
    }

    private fun show(place: Place, userRefresh: Boolean = false) {
        val token = ++loadToken
        val shown = home.place
        val samePlace = shown != null && shown.id == place.id && home.content != null &&
            (!place.isCurrentLocation || distanceKm(shown, place) <= 5)
        fetchPending = true
        if (samePlace) {
            setHome(home.copy(place = place, isRefreshing = userRefresh || home.isRefreshing, errorMessage = null))
        } else {
            setHome(HomeState(place = place, isLoading = true, isRefreshing = userRefresh, isLocating = isLocating))
            Async.run({ repository.cached(place) }) { result ->
                val cached = result.getOrNull()
                if (cached != null && token == loadToken && home.content == null) {
                    // The fetch may already have failed; only keep spinning while it is still running.
                    setHome(home.copy(content = ForecastContent.from(cached), isLoading = false, isRefreshing = fetchPending))
                }
            }
        }
        Async.run({ repository.fetch(place) }) { result ->
            if (token != loadToken) return@run
            fetchPending = false
            val forecast = result.getOrNull()
            if (forecast != null) {
                setHome(home.copy(place = place, content = ForecastContent.from(forecast), isLoading = false, isRefreshing = false, errorMessage = null))
                putSummary(place, forecast)
            } else {
                setHome(home.copy(isLoading = false, isRefreshing = false, errorMessage = "İnternet bağlantınızı kontrol edip tekrar deneyin."))
                if (userRefresh) message("Güncellenemedi. İnternet bağlantınızı kontrol edin.")
            }
        }
    }

    // endregion
    // region Places -------------------------------------------------------------------------

    fun onQueryChange(text: String) {
        if (text == query) return
        query = text
        Async.main.removeCallbacks(searchRunnable)
        // Responses for an earlier text must never be shown under the new one.
        searchToken++
        if (text.trim().length < 2) {
            isSearching = false
            searchResults = emptyList()
            searchError = null
        } else {
            isSearching = true
            searchError = null
            Async.main.postDelayed(searchRunnable, 350)
        }
        notifyChanged()
    }

    fun clearQuery() = onQueryChange("")

    private fun runSearch() {
        val text = query.trim()
        val token = ++searchToken
        Async.run({ repository.search(text) }) { result ->
            if (token != searchToken) return@run
            isSearching = false
            val places = result.getOrNull()
            if (places != null) {
                searchResults = places
                searchError = null
            } else {
                searchResults = emptyList()
                searchError = "İnternet bağlantınızı kontrol edip tekrar deneyin."
            }
            notifyChanged()
        }
    }

    fun remove(place: Place) {
        store.removePlace(place.id)
        summaries.remove(place.id)
        val settings = store.settings
        if (settings.notificationPlace?.id == place.id) {
            store.update { it.copy(notificationPlace = null, alertsEnabled = false) }
            JobScheduling.apply(app, store.settings)
            if (settings.alertsEnabled) message("${place.name} bildirim konumuydu; uyarı bildirimleri kapatıldı.")
        }
        WeatherWidget.requestRefresh(app)
        if (home.place?.id == place.id) {
            val next = store.places.firstOrNull()
            val current = currentPlace
            when {
                next != null -> select(next)
                current != null -> {
                    store.selectedId = Place.CURRENT_LOCATION_ID
                    show(current)
                }
                else -> {
                    loadToken++
                    setHome(HomeState(needsOnboarding = true))
                }
            }
        }
        notifyChanged()
    }

    /** Loads cached weather for the saved places list and refreshes stale entries. */
    fun refreshSummaries() {
        val all = store.places + listOfNotNull(currentPlace)
        all.filter { summariesInFlight.add(it.id) }.forEach { place ->
            // Background work: never queued in front of what the user is looking at.
            Async.runLow({
                val cached = repository.cached(place)
                val stale = cached == null || System.currentTimeMillis() - cached.fetchedAtMillis > 30 * 60_000L
                cached to if (stale) runCatching { repository.fetch(place) }.getOrNull() else null
            }) { result ->
                summariesInFlight.remove(place.id)
                val (cached, fetched) = result.getOrNull() ?: return@runLow
                (fetched ?: cached)?.let { putSummary(place, it) }
            }
        }
    }

    private fun putSummary(place: Place, forecast: Forecast) {
        WeatherWidget.onForecast(app, place, forecast)
        val now = forecast.localNow()
        val today = forecast.today(now)
        summaries[place.id] = PlaceSummary(
            place = place,
            temperature = forecast.current.temperature,
            weatherCode = forecast.current.weatherCode,
            isDay = forecast.current.isDay,
            high = today?.temperatureMax,
            low = today?.temperatureMin,
            topAlert = AlertEngine.evaluate(forecast, now).firstOrNull { !it.outlook },
        )
        notifyChanged()
    }

    // endregion
    // region Settings -----------------------------------------------------------------------

    fun setAlertsEnabled(enabled: Boolean) {
        store.update { it.copy(alertsEnabled = enabled, notificationPlace = it.notificationPlace ?: home.place) }
        JobScheduling.apply(app, store.settings)
        if (enabled) JobScheduling.checkNow(app)
        WeatherWidget.requestRefresh(app)
        notifyChanged()
    }

    fun setRainSoon(enabled: Boolean) {
        store.update { it.copy(rainSoon = enabled) }
        JobScheduling.apply(app, store.settings)
        notifyChanged()
    }

    fun setMinSeverity(severity: Severity) {
        store.update { it.copy(minSeverity = severity) }
        notifyChanged()
    }

    fun setMorningSummary(enabled: Boolean) {
        store.update { it.copy(morningSummary = enabled, notificationPlace = it.notificationPlace ?: home.place) }
        JobScheduling.apply(app, store.settings)
        WeatherWidget.requestRefresh(app)
        notifyChanged()
    }

    /** Ties notifications to [place] and switches alerts on. */
    fun setNotificationPlace(place: Place) {
        store.update { it.copy(notificationPlace = place, alertsEnabled = true) }
        JobScheduling.apply(app, store.settings)
        JobScheduling.checkNow(app)
        WeatherWidget.requestRefresh(app)
        message("${place.name} için hava uyarısı bildirimleri açık.")
        notifyChanged()
    }

    fun useViewingPlaceForNotifications() {
        home.place?.let(::setNotificationPlace)
    }

    // endregion

    private fun setHome(state: HomeState) {
        home = state
        notifyChanged()
    }

    private fun notifyChanged() {
        onChange?.invoke()
    }

    fun message(text: String) {
        onMessage?.invoke(text)
    }

    /** Approximate distance, good enough to tell "same town" from "moved". */
    private fun distanceKm(a: Place, b: Place): Double {
        val meanLat = Math.toRadians((a.latitude + b.latitude) / 2)
        val dx = Math.toRadians(b.longitude - a.longitude) * cos(meanLat)
        val dy = Math.toRadians(b.latitude - a.latitude)
        return sqrt(dx * dx + dy * dy) * 6371.0
    }
}
