package com.mindfullness.weather.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.ui.theme.AppColors

data class SettingsUiState(
    val alertsEnabled: Boolean = false,
    val minSeverity: Severity = Severity.YELLOW,
    val morningSummaryEnabled: Boolean = false,
    val notificationPlace: Place? = null,
    val viewingPlace: Place? = null,
    val version: String = "",
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAlertsChange: (Boolean) -> Unit,
    onMinSeverityChange: (Severity) -> Unit,
    onMorningSummaryChange: (Boolean) -> Unit,
    onUseViewingPlaceForNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
            .statusBarsPadding(),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri", tint = AppColors.OnSurface)
            }
            Text("Ayarlar", style = MaterialTheme.typography.titleLarge, color = AppColors.OnSurface)
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsSection("Bildirimler") {
                SwitchRow(
                    title = "Hava uyarıları",
                    text = "Önümüzdeki 24 saatte önemli bir hava olayı beklendiğinde bildirim alın. Tahmin yaklaşık 3 saatte bir kontrol edilir.",
                    checked = state.alertsEnabled,
                    onChange = onAlertsChange,
                )
                if (state.alertsEnabled) {
                    Divider()
                    Text(
                        "Hangi seviyede bildirilsin?",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.OnSurfaceMuted,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    SeverityOption("Sarı ve üzeri (önerilen)", Severity.YELLOW, state.minSeverity, onMinSeverityChange)
                    SeverityOption("Yalnızca turuncu ve kırmızı", Severity.ORANGE, state.minSeverity, onMinSeverityChange)
                    Divider()
                    NotificationPlaceRow(state, onUseViewingPlaceForNotifications)
                }
                Divider()
                SwitchRow(
                    title = "Sabah özeti",
                    text = "Her sabah 07:00 civarında günün hava durumu ve yanınıza almanız gerekenler.",
                    checked = state.morningSummaryEnabled,
                    onChange = onMorningSummaryChange,
                )
            }
            Spacer(Modifier.height(20.dp))
            SettingsSection("Uyarı seviyeleri") {
                LevelRow(Severity.INFO, "Bilgi", "Bilmeniz faydalı; örneğin hafif yağmur veya yüksek UV.")
                LevelRow(Severity.YELLOW, "Sarı · Dikkatli olun", "Günlük işleri etkileyebilir; tedbirli olun.")
                LevelRow(Severity.ORANGE, "Turuncu · Hazırlıklı olun", "Tehlikeli olabilir; planlarınızı gözden geçirin.")
                LevelRow(Severity.RED, "Kırmızı · Önlem alın", "Çok tehlikeli; önlem alın ve resmî uyarıları izleyin.")
            }
            Spacer(Modifier.height(20.dp))
            SettingsSection("Hakkında") {
                Text(
                    "Hava verileri Open-Meteo.com tarafından sağlanır (CC BY 4.0). Uyarılar tahmin modellerine dayalı " +
                        "otomatik değerlendirmelerdir; resmî uyarılar için MGM ve AFAD duyurularını takip edin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.OnSurfaceMuted,
                    modifier = Modifier.padding(16.dp),
                )
                if (state.version.isNotBlank()) {
                    Text(
                        "Sürüm ${state.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.OnSurfaceMuted,
                        modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = AppColors.Primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface),
        content = content,
    )
}

@Composable
private fun Divider() = HorizontalDivider(color = AppColors.SurfaceHigh, modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.OnSurface)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurfaceMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SeverityOption(label: String, value: Severity, selected: Severity, onSelect: (Severity) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AppColors.OnSurface)
    }
}

@Composable
private fun NotificationPlaceRow(state: SettingsUiState, onUseViewingPlace: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = AppColors.OnSurfaceMuted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Bildirim konumu", style = MaterialTheme.typography.titleMedium, color = AppColors.OnSurface)
            Text(
                state.notificationPlace?.let { if (it.isCurrentLocation) "${it.name} (mevcut konum)" else it.name } ?: "Seçilmedi",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.OnSurfaceMuted,
            )
        }
    }
    val viewing = state.viewingPlace
    if (viewing != null && !samePlace(viewing, state.notificationPlace)) {
        TextButton(onClick = onUseViewingPlace, modifier = Modifier.padding(start = 52.dp, bottom = 8.dp)) {
            Text("${viewing.name} için bildirim al")
        }
    }
}

private fun samePlace(a: Place, b: Place?): Boolean =
    b != null && (a.id == b.id)

@Composable
private fun LevelRow(severity: Severity, title: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(
            Modifier
                .padding(top = 4.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(AppColors.severity(severity)),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.OnSurface)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurfaceMuted)
        }
    }
}
