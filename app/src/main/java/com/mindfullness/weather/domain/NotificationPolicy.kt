package com.mindfullness.weather.domain

import org.threeten.bp.LocalDateTime

/** When the background check may announce an alert. */
object NotificationPolicy {
    /** Night hours in which non-urgent day-long alerts wait: 22:00–07:00. */
    private const val QUIET_END = 7
    private const val QUIET_START = 22

    /**
     * Whether [alert] should be announced now. Hourly hazards: up to 24 hours ahead, at any time.
     * Day-long ones (pollen, flood risk) start at midnight: tomorrow's is announced from 18:00 the
     * evening before, and unless urgent (orange or red, pollen aside) never during the night.
     */
    fun isDue(alert: WeatherAlert, now: LocalDateTime): Boolean {
        if (!alert.allDay) return alert.start.isBefore(now.plusHours(24))
        if (!alert.start.isBefore(now.plusHours(6))) return false
        val urgent = alert.type != AlertType.POLLEN && alert.severity >= Severity.ORANGE
        return urgent || now.hour in QUIET_END until QUIET_START
    }
}
