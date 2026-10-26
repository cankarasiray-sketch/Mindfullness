package com.mindfullness.weather.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rates an alert by how many independent weather models predict the same event. A model agrees
 * when it shows the hazard in roughly the same period: models often differ by an hour or two in
 * timing, which should not count as disagreement.
 */
internal object ModelAgreement {
    private const val SLACK_HOURS = 2L

    /** Ensemble members share one model, so only a smaller timing difference is forgiven. */
    private const val ENSEMBLE_SLACK_HOURS = 1L

    /** Fewer members with data than this give no meaningful probability. */
    private const val MIN_MEMBERS = 10

    /** Hours of one model around an alert: instant values and preceding-hour sums. */
    private class Window(val instants: List<ModelHour>, val sums: List<ModelHour>)

    fun rate(
        alert: WeatherAlert,
        hours: List<HourlyPoint>,
        models: List<ModelRun>,
        ensemble: List<ModelRun> = emptyList(),
    ): Confidence? {
        if (alert.outlook || alert.allDay || (models.isEmpty() && ensemble.isEmpty())) return null
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

        val (agreeing, total) = count(alert, models, SLACK_HOURS, agrees)
        val (inEnsemble, members) = count(alert, ensemble, ENSEMBLE_SLACK_HOURS, agrees)
        val probability = if (members >= MIN_MEMBERS) (inEnsemble * 100.0 / members).roundToInt() else null
        return when {
            total >= 2 -> Confidence(agreeing, total, probability)
            probability != null -> Confidence(0, 0, probability)
            else -> null
        }
    }

    /** How many of [runs] show the event, and how many have data for the period. */
    private fun count(alert: WeatherAlert, runs: List<ModelRun>, slack: Long, agrees: (Window) -> Boolean?): Pair<Int, Int> {
        val from = alert.start.minusHours(slack)
        val to = alert.end.plusHours(slack)
        var total = 0
        var agreeing = 0
        for (run in runs) {
            val window = Window(
                instants = run.hours.filter { !it.time.isBefore(from) && it.time.isBefore(to) },
                sums = run.hours.filter { it.time.isAfter(from) && !it.time.isAfter(to) },
            )
            // A run without data for the period neither agrees nor disagrees.
            val result = agrees(window) ?: continue
            total++
            if (result) agreeing++
        }
        return agreeing to total
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
