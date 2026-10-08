package com.mindfullness.weather.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.Severity
import com.mindfullness.weather.domain.WeatherAlert
import com.mindfullness.weather.ui.theme.AppColors
import java.time.LocalDateTime

/** "What to watch out for" – the core of the app, shown right below the current conditions. */
@Composable
fun AlertsSection(alerts: List<WeatherAlert>, now: LocalDateTime, modifier: Modifier = Modifier) {
    val upcoming = alerts.filter { !it.outlook }
    val outlook = alerts.filter { it.outlook }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(
            Icons.Rounded.NotificationsActive,
            "Dikkat edilmesi gerekenler",
            modifier = Modifier.padding(horizontal = 4.dp),
            trailing = "48 saat",
        )
        if (upcoming.none { it.severity >= Severity.YELLOW }) {
            AllClearCard(hasInfo = upcoming.isNotEmpty())
        }
        val firstImportant = upcoming.firstOrNull { it.severity >= Severity.YELLOW }
        upcoming.forEach { alert ->
            AlertCard(alert, now, initiallyExpanded = alert == firstImportant)
        }
        if (outlook.isNotEmpty()) {
            Text(
                "İleriki günler",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            outlook.forEach { alert -> AlertCard(alert, now, initiallyExpanded = false) }
        }
    }
}

@Composable
private fun AllClearCard(hasInfo: Boolean) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(AppColors.Good.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AppColors.Good)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "Önemli bir hava olayı beklenmiyor",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                )
                Text(
                    if (hasInfo) "Aşağıdaki küçük notlara göz atmanız yeterli." else "Önümüzdeki 48 saat için uyarı yok.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun AlertCard(alert: WeatherAlert, now: LocalDateTime, initiallyExpanded: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(alert.key) { mutableStateOf(initiallyExpanded) }
    val accent = AppColors.severity(alert.severity)
    val timeText = alert.whenText(now)

    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(AppColors.Glass)
            .border(1.dp, accent.copy(alpha = 0.35f), CardShape)
            .drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(alertIcon(alert.type), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(alert.title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                Text(timeText, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Pill(alert.severity.shortLabel, accent, AppColors.onSeverity(alert.severity))
        }
        Spacer(Modifier.height(10.dp))
        Text(alert.detail, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Ne yapmalı?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                if (alert.severity != Severity.INFO) {
                    Text(alert.severity.label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
                }
            }
            Spacer(Modifier.height(6.dp))
            alert.advice.forEach { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Box(
                        Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(accent),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "Gizle" else "Öneriler",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextTertiary,
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
