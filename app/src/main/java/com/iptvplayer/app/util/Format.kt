package com.iptvplayer.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Time/date formatting helpers for the UI (device timezone). */
object Format {

    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dayName: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun time(epochSec: Long): String =
        hhmm.format(Instant.ofEpochSecond(epochSec).atZone(zone()))

    fun timeRange(startSec: Long, stopSec: Long): String =
        "${time(startSec)} – ${time(stopSec)}"

    fun date(epochSec: Long): String =
        dayName.format(Instant.ofEpochSecond(epochSec).atZone(zone()))

    fun dayStartEpochSec(dayOffset: Int): Long =
        LocalDate.now(zone()).plusDays(dayOffset.toLong())
            .atStartOfDay(zone()).toEpochSecond()

    fun dayEndEpochSec(dayOffset: Int): Long = dayStartEpochSec(dayOffset) + 24 * 3600

    /** "1:23:45" or "12:34" */
    fun msToClock(ms: Long): String {
        val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Relative "last update" text. */
    fun relativeAgo(tsMs: Long): String {
        if (tsMs <= 0) return ""
        val diff = System.currentTimeMillis() - tsMs
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / 60 / 24}d"
        }
    }
}
