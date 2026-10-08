package com.mindfullness.weather.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Rates an alert by how many independent weather models predict the same event. A model agrees
 * when it shows the hazard in roughly the same period: models often differ by an hour or two in
 * timing, which should not count as disagreement.
 */
internal object ModelAgreement {
    private const val SLACK_HOURS = 2L

    /** Hours of one model around an alert: instant values and preceding-hour sums. */
    private class Window(val instants: List<ModelHour>, val sums: List<ModelHour>)

    fun rate(alert: WeatherAlert, hours: List<HourlyPoint>, models: List<ModelRun>): Confidence? {
        if (alert.outlook || alert.allDay || models.isEmpty()) return null
        // Instant values describe the hour from their timestamp, sums the hour up to it.
        val instants = hours.filter { !it.time.isBefore(alert.start) && it.time.isBefore(alert.end) }
        val sums = hours.filter { it.time.isAfter(alert.start) && !it.time.isAfter(alert.end) }
        val agrees: (Window) -> Boolean? = when (alert.type) {
            AlertType.RAIN, AlertType.THUNDERSTORM -> {
                val total = sums.sumOf { it.precipitation }
                val light = alert.type == AlertType.RAIN && total < 2
                precipitationAtLeast(if (light) 0.2 else max(1.0, total * 0.3))
            }
            AlertType.SNOW -> snowAtLeast(max(0.3, sums.sumOf { it.precipitation } * 0.3))
            AlertType.WIND -> gustsAtLeast(max(45.0, (instants.maxOfOrNull { it.windGusts } ?: return null) * 0.8))
            AlertType.HEAT -> warmestAtLeast((instants.maxOfOrNull { it.temperature } ?: return null) - 2)
            AlertType.COLD -> coldestAtMost(min((instants.minOfOrNull { it.temperature } ?: return null) + 2, 3.0))
            else -> return null
        }

        val from = alert.start.minusHours(SLACK_HOURS)
        val to = alert.end.plusHours(SLACK_HOURS)
        var total = 0
        var agreeing = 0
        for (model in models) {
            val window = Window(
                instants = model.hours.filter { !it.time.isBefore(from) && it.time.isBefore(to) },
                sums = model.hours.filter { it.time.isAfter(from) && !it.time.isAfter(to) },
            )
            // A model without data for the period neither agrees nor disagrees.
            val result = agrees(window) ?: continue
            total++
            if (result) agreeing++
        }
        return if (total >= 2) Confidence(agreeing, total) else null
    }

    private fun precipitationAtLeast(needed: Double): (Window) -> Boolean? =
        { window -> window.sums.total { it.precipitation }?.let { it >= needed } }

    private fun snowAtLeast(needed: Double): (Window) -> Boolean? = { window ->
        val precipitation = window.sums.total { it.precipitation }
        val coldest = window.instants.mapNotNull { it.temperature }.minOrNull()
        if (precipitation == null || coldest == null) null else precipitation >= needed && coldest <= 1.5
    }

    private fun gustsAtLeast(needed: Double): (Window) -> Boolean? =
        { window -> window.instants.mapNotNull { it.windGusts }.maxOrNull()?.let { it >= needed } }

    private fun warmestAtLeast(needed: Double): (Window) -> Boolean? =
        { window -> window.instants.mapNotNull { it.temperature }.maxOrNull()?.let { it >= needed } }

    private fun coldestAtMost(needed: Double): (Window) -> Boolean? =
        { window -> window.instants.mapNotNull { it.temperature }.minOrNull()?.let { it <= needed } }

    /** Sum of the known values, or null when the model has no data for the period. */
    private fun List<ModelHour>.total(value: (ModelHour) -> Double?): Double? {
        val known = mapNotNull(value)
        return if (known.isEmpty()) null else known.sum()
    }
}
