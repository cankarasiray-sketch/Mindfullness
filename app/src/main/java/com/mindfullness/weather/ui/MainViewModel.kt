package com.mindfullness.weather.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mindfullness.weather.BuildConfig
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.platform.AppGraph
import com.mindfullness.weather.platform.DeviceLocationProvider
import com.mindfullness.weather.platform.SettingsStore
import com.mindfullness.weather.platform.WorkScheduler
import com.mindfullness.weather.ui.home.ForecastContent
import com.mindfullness.weather.ui.home.HomeUiState
import com.mindfullness.weather.ui.places.PlaceSummary
import com.mindfullness.weather.ui.places.PlacesUiState
import com.mindfullness.weather.ui.settings.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val repository = AppGraph.repository(application)
    private val location = DeviceLocationProvider(application)

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val query = MutableStateFlow("")
    private val search = MutableStateFlow(SearchState())
    private val summaries = MutableStateFlow<Map<Long, PlaceSummary>>(emptyMap())
    private val locating = MutableStateFlow(false)
    private val currentPlace = MutableStateFlow(store.lastCurrentPlace)

    val places: StateFlow<PlacesUiState> = combine(
        combine(query, search, ::Pair),
        store.places,
        summaries,
        combine(locating, currentPlace, ::Pair),
        combine(_home, store.settings, ::Pair),
    ) { (q, s), saved, summaryMap, (isLocating, current), (home, settings) ->
        PlacesUiState(
            query = q,
            isSearching = s.isSearching,
            results = s.results,
            searchError = s.error,
            current = current?.let { summaryMap[Place.CURRENT_LOCATION_ID] ?: PlaceSummary(it) },
            isLocating = isLocating,
            saved = saved.map { summaryMap[it.id] ?: PlaceSummary(it) },
            selectedId = home.place?.id,
            notificationPlaceId = settings.notificationPlace?.id.takeIf { settings.alertsEnabled },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlacesUiState())

    val settings: StateFlow<SettingsUiState> = combine(store.settings, _home) { settings, home ->
        SettingsUiState(
            alertsEnabled = settings.alertsEnabled,
            minSeverity = settings.minSeverity,
            morningSummaryEnabled = settings.morningSummary,
            notificationPlace = settings.notificationPlace,
            viewingPlace = home.place,
            version = BuildConfig.VERSION_NAME,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var locateJob: Job? = null

    init {
        restoreSelection()
        observeSearch()
    }

    // region Home ------------------------------------------------------------------------------

    private fun restoreSelection() {
        val selected = store.selectedId
        val savedPlace = store.places.value.firstOrNull { it.id == selected }
        when {
            selected == Place.CURRENT_LOCATION_ID && location.hasPermission() -> useCurrentLocation()
            selected == Place.CURRENT_LOCATION_ID && currentPlace.value != null -> show(currentPlace.value!!)
            savedPlace != null -> show(savedPlace)
            store.places.value.isNotEmpty() -> select(store.places.value.first())
            else -> _home.value = HomeUiState(needsOnboarding = true)
        }
    }

    fun select(place: Place) {
        if (place.isCurrentLocation) {
            useCurrentLocation()
            return
        }
        store.addPlace(place)
        store.selectedId = place.id
        query.value = ""
        show(place)
    }

    fun hasLocationPermission() = location.hasPermission()

    fun useCurrentLocation(userRefresh: Boolean = false) {
        store.selectedId = Place.CURRENT_LOCATION_ID
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            locating.value = true
            _home.update {
                it.copy(
                    isLocating = true,
                    isRefreshing = userRefresh,
                    place = if (it.place?.isCurrentLocation == true) it.place else currentPlace.value ?: it.place,
                )
            }
            val place = try {
                location.currentPlace()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            locating.value = false
            _home.update { it.copy(isLocating = false) }
            if (place != null) {
                currentPlace.value = place
                store.lastCurrentPlace = place
                // Keep background alerts following the device when they are tied to "current location".
                if (store.settings.value.notificationPlace?.isCurrentLocation == true) {
                    store.update { it.copy(notificationPlace = place) }
                }
                show(place, userRefresh)
            } else {
                val fallback = currentPlace.value
                if (fallback != null) {
                    message("Konum alınamadı; son bilinen konum gösteriliyor.")
                    show(fallback, userRefresh)
                } else {
                    message("Konum alınamadı. Konum servislerini açın veya bir şehir arayın.")
                    _home.update { if (it.content == null) HomeUiState(needsOnboarding = true) else it.copy(isRefreshing = false) }
                }
            }
        }
    }

    fun onLocationDenied() {
        message("Konum izni verilmedi. Dilediğiniz yeri arayarak hava durumuna bakabilirsiniz.")
    }

    fun refresh() {
        val place = _home.value.place ?: return
        if (place.isCurrentLocation && location.hasPermission()) useCurrentLocation(userRefresh = true)
        else show(place, userRefresh = true)
    }

    /** Re-derives time-based content and refetches when the data is older than 30 minutes. */
    fun onResume() {
        val state = _home.value
        val content = state.content ?: return
        _home.update { it.copy(content = ForecastContent.from(content.forecast)) }
        val age = System.currentTimeMillis() - content.forecast.fetchedAtMillis
        if (age > 30 * 60_000L && loadJob?.isActive != true && locateJob?.isActive != true) {
            state.place?.let { show(it) }
        }
    }

    private fun show(place: Place, userRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val samePlace = _home.value.place?.let { it.id == place.id } == true && _home.value.content != null
            if (!samePlace) {
                val cached = withContext(Dispatchers.IO) { repository.cached(place) }
                _home.value = HomeUiState(
                    place = place,
                    content = cached?.let { ForecastContent.from(it) },
                    isLoading = cached == null,
                    isRefreshing = userRefresh,
                )
            } else {
                _home.update { it.copy(place = place, isRefreshing = userRefresh, errorMessage = null) }
            }
            try {
                val forecast = repository.fetch(place)
                _home.update {
                    it.copy(content = ForecastContent.from(forecast), isLoading = false, isRefreshing = false, errorMessage = null)
                }
                putSummary(place, forecast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _home.update {
                    it.copy(isLoading = false, isRefreshing = false, errorMessage = "İnternet bağlantınızı kontrol edip tekrar deneyin.")
                }
                if (userRefresh) message("Güncellenemedi. İnternet bağlantınızı kontrol edin.")
            }
        }
    }

    // endregion
    // region Places -------------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            query.map { it.trim() }.distinctUntilChanged().debounce(350).collectLatest { text ->
                if (text.length < 2) {
                    search.value = SearchState()
                    return@collectLatest
                }
                search.update { it.copy(isSearching = true, error = null) }
                search.value = try {
                    SearchState(results = repository.search(text))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SearchState(error = "İnternet bağlantınızı kontrol edip tekrar deneyin.")
                }
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun remove(place: Place) {
        store.removePlace(place.id)
        summaries.update { it - place.id }
        if (store.settings.value.notificationPlace?.id == place.id) {
            store.update { it.copy(notificationPlace = null, alertsEnabled = false) }
            WorkScheduler.apply(getApplication<Application>(), store.settings.value)
            message("${place.name} bildirim konumuydu; uyarı bildirimleri kapatıldı.")
        }
        if (_home.value.place?.id == place.id) {
            val next = store.places.value.firstOrNull()
            when {
                next != null -> select(next)
                currentPlace.value != null -> show(currentPlace.value!!)
                else -> {
                    loadJob?.cancel()
                    _home.value = HomeUiState(needsOnboarding = true)
                }
            }
        }
    }

    /** Loads cached weather for the saved places list and refreshes stale entries in parallel. */
    fun refreshSummaries() {
        viewModelScope.launch {
            val all = store.places.value + listOfNotNull(currentPlace.value)
            val stale = all.filter { place ->
                val cached = withContext(Dispatchers.IO) { repository.cached(place) }
                if (cached != null) putSummary(place, cached)
                cached == null || System.currentTimeMillis() - cached.fetchedAtMillis > 30 * 60_000L
            }
            stale.map { place ->
                async {
                    try {
                        putSummary(place, repository.fetch(place))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep the cached summary; the list is best effort.
                    }
                }
            }.awaitAll()
        }
    }

    private fun putSummary(place: Place, forecast: Forecast) {
        val now = forecast.localNow()
        val today = forecast.today(now)
        val summary = PlaceSummary(
            place = place,
            temperature = forecast.current.temperature,
            weatherCode = forecast.current.weatherCode,
            isDay = forecast.current.isDay,
            high = today?.temperatureMax,
            low = today?.temperatureMin,
            topAlert = AlertEngine.evaluate(forecast, now).firstOrNull { !it.outlook },
        )
        summaries.update { it + (place.id to summary) }
    }

    // endregion
    // region Settings -----------------------------------------------------------------------

    fun setAlertsEnabled(enabled: Boolean) {
        store.update {
            it.copy(alertsEnabled = enabled, notificationPlace = it.notificationPlace ?: _home.value.place)
        }
        WorkScheduler.apply(getApplication<Application>(), store.settings.value)
        if (enabled) WorkScheduler.checkNow(getApplication<Application>())
    }

    fun setMinSeverity(severity: Severity) {
        store.update { it.copy(minSeverity = severity) }
    }

    fun setMorningSummary(enabled: Boolean) {
        store.update {
            it.copy(morningSummary = enabled, notificationPlace = it.notificationPlace ?: _home.value.place)
        }
        WorkScheduler.apply(getApplication<Application>(), store.settings.value)
    }

    /** Ties notifications to [place] and switches alerts on. */
    fun setNotificationPlace(place: Place) {
        store.update { it.copy(notificationPlace = place, alertsEnabled = true) }
        WorkScheduler.apply(getApplication<Application>(), store.settings.value)
        WorkScheduler.checkNow(getApplication<Application>())
        message("${place.name} için hava uyarısı bildirimleri açık.")
    }

    fun useViewingPlaceForNotifications() {
        _home.value.place?.let(::setNotificationPlace)
    }

    fun message(text: String) {
        _messages.trySend(text)
    }

    // endregion

    private data class SearchState(
        val isSearching: Boolean = false,
        val results: List<Place> = emptyList(),
        val error: String? = null,
    )
}
