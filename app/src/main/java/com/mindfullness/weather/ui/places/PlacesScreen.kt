package com.mindfullness.weather.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.ui.components.Pill
import com.mindfullness.weather.ui.components.WeatherIcon
import com.mindfullness.weather.ui.components.deg
import com.mindfullness.weather.ui.theme.AppColors
import java.util.Locale

data class PlaceSummary(
    val place: Place,
    val temperature: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean = true,
    val high: Double? = null,
    val low: Double? = null,
    val topAlert: WeatherAlert? = null,
)

data class PlacesUiState(
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

private val ScreenBackground = Color(0xFF0B1220)
private val RowShape = RoundedCornerShape(20.dp)

@Composable
fun PlacesScreen(
    state: PlacesUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (Place) -> Unit,
    onSelectCurrentLocation: () -> Unit,
    onRemove: (Place) -> Unit,
    onSetNotificationPlace: (Place) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Jump straight into typing when there is nothing saved yet.
        if (state.saved.isEmpty()) runCatching { focusRequester.requestFocus() }
    }
    Column(
        modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri", tint = AppColors.OnSurface)
            }
            TextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Şehir, ilçe veya yer ara") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Temizle")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppColors.SurfaceHigh,
                    unfocusedContainerColor = AppColors.SurfaceHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = AppColors.Primary,
                ),
            )
        }

        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.query.isBlank()) {
                item("current") {
                    CurrentLocationRow(state.current, state.isLocating, state.selectedId == Place.CURRENT_LOCATION_ID, onSelectCurrentLocation)
                }
                item("saved-header") { Header("Kayıtlı yerler") }
                if (state.saved.isEmpty()) {
                    item("saved-empty") {
                        Hint(
                            Icons.Rounded.TravelExplore,
                            "Henüz kayıtlı yer yok",
                            "Yukarıdan bir şehir veya ilçe arayın. Baktığınız yerler burada listelenir; tek dokunuşla geçiş yapabilirsiniz.",
                        )
                    }
                }
                items(state.saved, key = { "saved-${it.place.id}" }) { summary ->
                    SavedPlaceRow(
                        summary = summary,
                        selected = summary.place.id == state.selectedId,
                        notifies = summary.place.id == state.notificationPlaceId,
                        onClick = { onSelect(summary.place) },
                        onRemove = { onRemove(summary.place) },
                        onSetNotificationPlace = { onSetNotificationPlace(summary.place) },
                    )
                }
            } else {
                when {
                    state.isSearching && state.results.isEmpty() -> item("searching") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                    state.searchError != null -> item("error") {
                        Hint(Icons.Rounded.Search, "Arama yapılamadı", state.searchError)
                    }
                    state.results.isEmpty() && state.query.trim().length >= 2 -> item("empty") {
                        Hint(Icons.Rounded.Search, "Sonuç bulunamadı", "Yazımı kontrol edin veya daha genel bir yer adı deneyin.")
                    }
                }
                items(state.results, key = { "result-${it.id}" }) { place ->
                    SearchResultRow(place, onClick = { onSelect(place) })
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = AppColors.OnSurfaceMuted,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = AppColors.OnSurfaceMuted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.OnSurface)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurfaceMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CurrentLocationRow(summary: PlaceSummary?, isLocating: Boolean, selected: Boolean, onClick: () -> Unit) {
    PlaceRowContainer(selected, onClick) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(AppColors.Primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.NearMe, contentDescription = null, tint = AppColors.Primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Mevcut konum", style = MaterialTheme.typography.titleMedium, color = AppColors.OnSurface)
            Text(
                when {
                    isLocating -> "Konum bulunuyor…"
                    summary != null -> summary.place.name
                    else -> "Bulunduğunuz yerin hava durumu"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isLocating) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else if (summary != null) {
            WeatherBadge(summary)
        }
    }
}

@Composable
private fun SavedPlaceRow(
    summary: PlaceSummary,
    selected: Boolean,
    notifies: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onSetNotificationPlace: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    PlaceRowContainer(selected, onClick) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.place.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (notifies) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.NotificationsActive, contentDescription = "Bildirim konumu", tint = AppColors.Primary, modifier = Modifier.size(16.dp))
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Check, contentDescription = "Seçili", tint = AppColors.Good, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                summary.place.subtitle.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summary.topAlert?.takeIf { it.severity >= Severity.YELLOW }?.let { alert ->
                Spacer(Modifier.height(6.dp))
                Pill(alert.title, AppColors.severity(alert.severity), AppColors.onSeverity(alert.severity))
            }
        }
        WeatherBadge(summary)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Seçenekler", tint = AppColors.OnSurfaceMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Bildirimleri bu yer için al") },
                    leadingIcon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
                    onClick = { menuOpen = false; onSetNotificationPlace() },
                    enabled = !notifies,
                )
                DropdownMenuItem(
                    text = { Text("Listeden kaldır") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun WeatherBadge(summary: PlaceSummary) {
    if (summary.temperature == null || summary.weatherCode == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(summary.weatherCode, summary.isDay, size = 34.dp)
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(summary.temperature.deg(), style = MaterialTheme.typography.titleLarge, color = AppColors.OnSurface)
            if (summary.high != null && summary.low != null) {
                Text(
                    "${summary.high.deg()} / ${summary.low.deg()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.OnSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun PlaceRowContainer(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(AppColors.Surface)
            .border(1.dp, if (selected) AppColors.Primary.copy(alpha = 0.6f) else AppColors.SurfaceHigh, RowShape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun SearchResultRow(place: Place, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(AppColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            val flag = place.countryCode?.let(::flagEmoji)
            if (flag != null) Text(flag, style = MaterialTheme.typography.titleMedium)
            else Icon(Icons.Rounded.Place, contentDescription = null, tint = AppColors.OnSurfaceMuted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, color = AppColors.OnSurface)
            if (place.subtitle.isNotBlank()) {
                Text(place.subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Two-letter country code to its flag emoji, e.g. "TR" -> 🇹🇷. */
fun flagEmoji(countryCode: String): String? {
    val code = countryCode.uppercase(Locale.ROOT)
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
