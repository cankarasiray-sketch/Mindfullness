package com.mindfullness.weather.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.Sanitizer
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.AlertType
import com.mindfullness.weather.domain.TipKind
import com.mindfullness.weather.ui.theme.AppColors
import java.util.Locale

val CardShape = RoundedCornerShape(24.dp)
private val TR = Locale.forLanguageTag("tr-TR")

/** Translucent card used for every section on top of the sky gradient. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(AppColors.Glass)
            .border(1.dp, AppColors.GlassBorder, CardShape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SectionTitle(icon: ImageVector, text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text.uppercase(TR),
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = AppColors.TextTertiary)
        }
    }
}

@Composable
fun Pill(text: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

val SectionSpacing = Arrangement.spacedBy(12.dp)

fun alertIcon(type: AlertType): ImageVector = when (type) {
    AlertType.RAIN -> Icons.Rounded.Umbrella
    AlertType.SNOW, AlertType.COLD, AlertType.ICE -> Icons.Rounded.AcUnit
    AlertType.THUNDERSTORM -> Icons.Rounded.FlashOn
    AlertType.WIND -> Icons.Rounded.Air
    AlertType.HEAT -> Icons.Rounded.Thermostat
    AlertType.FOG -> Icons.Rounded.Dehaze
    AlertType.UV -> Icons.Rounded.WbSunny
    AlertType.TEMPERATURE_DROP -> Icons.AutoMirrored.Rounded.TrendingDown
}

fun tipIcon(kind: TipKind): ImageVector = when (kind) {
    TipKind.UMBRELLA -> Icons.Rounded.Umbrella
    TipKind.WARM_COAT, TipKind.JACKET -> Icons.Rounded.Checkroom
    TipKind.SUNSCREEN -> Icons.Rounded.Sanitizer
    TipKind.SUNGLASSES -> Icons.Rounded.WbSunny
    TipKind.WATER -> Icons.Rounded.LocalDrink
    TipKind.BOOTS -> Icons.Rounded.Hiking
    TipKind.WINDBREAKER -> Icons.Rounded.Air
}
