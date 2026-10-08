package com.mindfullness.weather.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.TimeText
import com.mindfullness.weather.ui.components.AlertsSection
import com.mindfullness.weather.ui.components.CurrentConditions
import com.mindfullness.weather.ui.components.DailyCard
import com.mindfullness.weather.ui.components.DetailsGrid
import com.mindfullness.weather.ui.components.GlassCard
import com.mindfullness.weather.ui.components.HourlyCard
import com.mindfullness.weather.ui.components.PrecipitationCard
import com.mindfullness.weather.ui.components.TipsRow
import com.mindfullness.weather.ui.components.WeatherIcon
import com.mindfullness.weather.ui.components.alertDays
import com.mindfullness.weather.ui.theme.AppColors
import com.mindfullness.weather.ui.theme.skyBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.content?.forecast?.current
    val background = if (current != null) skyBrush(current.weatherCode, current.isDay) else DefaultSky

    Box(modifier.fillMaxSize().background(background)) {
        when {
            state.needsOnboarding -> WelcomeContent(onUseLocation, onOpenPlaces, state.isLocating)
            state.content == null && (state.isLoading || state.isLocating) -> CenteredProgress(
                if (state.isLocating) "Konumunuz bulunuyor…" else "Hava durumu yükleniyor…",
            )
            state.content == null -> ErrorContent(state.errorMessage, onRefresh, onOpenPlaces)
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                ForecastList(state, state.content)
            }
        }
        if (!state.needsOnboarding) {
            TopBar(state.place, onOpenPlaces, onOpenSettings, Modifier.align(Alignment.TopCenter))
        }
    }
}

private val DefaultSky = Brush.verticalGradient(listOf(Color(0xFF1D3B6A), Color(0xFF0F1F3D), Color(0xFF070F21)))

@Composable
private fun TopBar(place: Place?, onOpenPlaces: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenPlaces)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (place?.isCurrentLocation == true) {
                Icon(Icons.Rounded.NearMe, contentDescription = "Mevcut konum", tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    place?.name ?: "Konum seçin",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = place?.subtitle.orEmpty()
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = AppColors.TextSecondary)
        }
        IconButton(onClick = onOpenPlaces) {
            Icon(Icons.Rounded.Search, contentDescription = "Yer ara", tint = AppColors.TextPrimary)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Ayarlar", tint = AppColors.TextPrimary)
        }
    }
}

@Composable
private fun ForecastList(state: HomeUiState, content: ForecastContent) {
    val forecast = content.forecast
    val now = content.now
    val today = forecast.today(now)
    val navigationPadding = WindowInsets.navigationBars.asPaddingValues()
    val statusPadding = WindowInsets.statusBars.asPaddingValues()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 72.dp + statusPadding.calculateTopPadding(),
            bottom = 24.dp + navigationPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.errorMessage != null || content.isStale) {
            item("stale") { StaleBanner(forecast.fetchedAtMillis, state.errorMessage != null) }
        }
        item("hero") { CurrentConditions(forecast.current, today) }
        item("alerts") { AlertsSection(content.alerts, now) }
        if (content.tips.isNotEmpty()) {
            item("tips") { TipsRow(content.tips) }
        }
        item("precipitation") { PrecipitationCard(content.precipitation, forecast.hoursFrom(now, 24)) }
        item("hourly") { HourlyCard(forecast.hoursFrom(now, 36)) }
        item("daily") {
            DailyCard(
                days = forecast.daily.filter { !it.date.isBefore(now.toLocalDate()) },
                today = now.toLocalDate(),
                alertDays = alertDays(content.alerts),
            )
        }
        item("details") { DetailsGrid(forecast.current, forecast.hourAt(now), today) }
        item("footer") {
            Text(
                "Son güncelleme: ${TimeText.ago(forecast.fetchedAtMillis)} · Veri: Open-Meteo.com (CC BY 4.0)\n" +
                    "Uyarılar model tahminlerine dayanır; resmî uyarılar için MGM'yi takip edin.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StaleBanner(fetchedAtMillis: Long, failed: Boolean) {
    val color by animateColorAsState(if (failed) Color(0x33FF922B) else Color(0x22FFFFFF), label = "stale")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            (if (failed) "Güncellenemedi" else "Veriler eski") + " · son güncelleme ${TimeText.ago(fetchedAtMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun CenteredProgress(text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = AppColors.TextPrimary, strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
    }
}

@Composable
private fun ErrorContent(message: String?, onRetry: () -> Unit, onOpenPlaces: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Hava durumu alınamadı", style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            message ?: "İnternet bağlantınızı kontrol edip tekrar deneyin.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Tekrar dene")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenPlaces) { Text("Başka bir yer ara") }
    }
}

@Composable
private fun WelcomeContent(onUseLocation: () -> Unit, onSearch: () -> Unit, isLocating: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        WeatherIcon(code = 81, isDay = true, size = 120.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Hava Uyarı",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold),
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Yağış, fırtına, don, sis ve aşırı sıcak gibi durumları önceden görün; ne zaman ve ne kadar ciddi olacağını, nasıl önlem alacağınızı öğrenin.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        GlassCard {
            Feature("Renkli uyarı seviyeleri", "Sarı, turuncu, kırmızı — MGM'nin kullandığı ölçekle.")
            Spacer(Modifier.height(12.dp))
            Feature("Saat saat yağış", "Yağmurun ne zaman başlayıp biteceğini grafikte görün.")
            Spacer(Modifier.height(12.dp))
            Feature("Her yeri arayın", "İstediğiniz şehir veya ilçenin hava durumuna bakın, kaydedin.")
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onUseLocation,
            enabled = !isLocating,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0F2C63)),
        ) {
            if (isLocating) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF0F2C63))
            } else {
                Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(if (isLocating) "Konum bulunuyor…" else "Konumumu kullan")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Şehir veya ilçe ara")
        }
    }
}

@Composable
private fun Feature(title: String, text: String) {
    Row {
        Box(
            Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(50)).background(AppColors.Sun),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        }
    }
}
