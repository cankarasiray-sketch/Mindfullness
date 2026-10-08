package com.mindfullness.weather.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mindfullness.weather.domain.AlertEngine
import com.mindfullness.weather.domain.CurrentWeather
import com.mindfullness.weather.domain.DailyPoint
import com.mindfullness.weather.domain.HourlyPoint
import com.mindfullness.weather.domain.TimeText
import com.mindfullness.weather.ui.theme.AppColors
import kotlin.math.roundToInt

private data class Tile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val note: String,
    val iconRotation: Float = 0f,
)

@Composable
fun DetailsGrid(current: CurrentWeather, hour: HourlyPoint?, today: DailyPoint?, modifier: Modifier = Modifier) {
    val tiles = buildList {
        val feelsDiff = current.apparentTemperature - current.temperature
        add(
            Tile(
                Icons.Rounded.Thermostat, "Hissedilen", current.apparentTemperature.deg(),
                when {
                    feelsDiff <= -2 -> "Rüzgar nedeniyle daha soğuk"
                    feelsDiff >= 2 -> "Nem nedeniyle daha sıcak"
                    else -> "Gerçek sıcaklığa yakın"
                },
            ),
        )
        add(
            Tile(
                Icons.Rounded.Opacity, "Nem", "%${current.humidity}",
                when {
                    current.humidity < 30 -> "Kuru hava"
                    current.humidity < 60 -> "Konforlu"
                    current.humidity < 80 -> "Nemli"
                    else -> "Çok nemli"
                },
            ),
        )
        add(
            Tile(
                Icons.Rounded.Navigation, "Rüzgar · ${compass(current.windDirection)}",
                "${current.windSpeed.roundToInt()} km/sa",
                "Hamle ${current.windGusts.roundToInt()} km/sa",
                // The arrow points where the wind blows to.
                iconRotation = (current.windDirection + 180).toFloat(),
            ),
        )
        val uv = hour?.uvIndex
        if (uv != null) {
            add(Tile(Icons.Rounded.WbSunny, "UV indeksi", uv.roundToInt().toString(), uvLabel(uv)))
        } else {
            add(Tile(Icons.Rounded.Air, "Hamle", "${current.windGusts.roundToInt()} km/sa", "Anlık en yüksek rüzgar"))
        }
        hour?.visibility?.let { visibility ->
            add(
                Tile(
                    Icons.Rounded.Visibility, "Görüş",
                    if (visibility >= 1000) "${AlertEngine.formatAmount(visibility / 1000)} km" else "${visibility.roundToInt()} m",
                    when {
                        visibility < 1000 -> "Sisli, dikkatli sürün"
                        visibility < 5000 -> "Puslu"
                        else -> "Açık"
                    },
                ),
            )
        }
        add(
            Tile(
                Icons.Rounded.Speed, "Basınç", "${current.pressure.roundToInt()} hPa",
                when {
                    current.pressure < 1005 -> "Alçak basınç"
                    current.pressure > 1022 -> "Yüksek basınç"
                    else -> "Normal"
                },
            ),
        )
        if (today?.sunrise != null && today.sunset != null) {
            add(Tile(Icons.Rounded.WbTwilight, "Gün doğumu", TimeText.hour(today.sunrise), "Gün batımı ${TimeText.hour(today.sunset)}"))
        }
        if (today != null) {
            add(
                Tile(
                    Icons.Rounded.Umbrella, "Bugün yağış", "${AlertEngine.formatAmount(today.precipitationSum)} mm",
                    today.precipitationProbabilityMax?.let { "En yüksek olasılık %$it" } ?: "Günlük toplam",
                ),
            )
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(Icons.Rounded.Info, "Ayrıntılar", Modifier.padding(horizontal = 4.dp))
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> DetailTile(tile, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailTile(tile: Tile, modifier: Modifier) {
    Column(
        modifier
            .clip(CardShape)
            .background(AppColors.Glass)
            .border(1.dp, AppColors.GlassBorder, CardShape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                tile.icon,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(15.dp).rotate(tile.iconRotation),
            )
            Spacer(Modifier.width(6.dp))
            Text(tile.label, style = MaterialTheme.typography.labelMedium, color = AppColors.TextTertiary, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Text(tile.value, style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(tile.note, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 2)
    }
}

private val CompassPoints = listOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")

/** Turkish 8-point compass label for a meteorological direction (where the wind comes from). */
fun compass(degrees: Double): String = CompassPoints[(((degrees % 360) + 360) % 360 / 45.0).roundToInt() % 8]
