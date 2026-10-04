package com.iptvplayer.app.player

import com.iptvplayer.app.data.db.ChannelEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds catch-up (archive) URLs for a channel.
 * Supports:
 *  - explicit `catchup-source` templates ({utc}, {utcend}, {lutc}, {duration}, …);
 *  - M3U catchup modes: default / append / flussonic / shift;
 *  - Xtream `tv_archive` streams (start=yyyy-MM-dd:HH-mm&duration=min).
 */
object Catchup {

    private val xcFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(ZoneOffset.UTC)

    /** Returns a playable catch-up url or null when not available. */
    fun buildUrl(channel: ChannelEntity, startSec: Long, endSec: Long, nowSec: Long): String? {
        if (!channel.catchupAvailable) return null
        if (startSec <= 0 || endSec <= startSec) return null

        val template = channel.catchupSource
        if (!template.isNullOrBlank()) {
            return interpolate(template, channel, startSec, endSec, nowSec)
        }

        val sep = if (channel.url.contains('?')) '&' else '?'
        return when (channel.catchupMode?.lowercase()) {
            "append", "default" ->
                channel.url + sep + "utc=$startSec&utcend=$endSec"

            "flussonic", "shift" -> {
                val minutesAgo = ((nowSec - startSec) / 60).coerceAtLeast(1)
                channel.url + sep + "timeshift=$minutesAgo"
            }

            else -> xtreamFallback(channel, startSec, endSec, sep)
        }
    }

    /** "Watch from the beginning of a programme" helper. */
    fun fromProgrammeStart(channel: ChannelEntity, programmeStartSec: Long, nowSec: Long): String? =
        buildUrl(channel, programmeStartSec, nowSec, nowSec)

    private fun xtreamFallback(
        channel: ChannelEntity,
        startSec: Long,
        endSec: Long,
        sep: Char,
    ): String? {
        if (!channel.url.contains("/live/")) return null
        val durationMin = ((endSec - startSec + 59) / 60).coerceAtLeast(1)
        return channel.url + sep + "start=" + xcFormat.format(Instant.ofEpochSecond(startSec)) +
            "&duration=$durationMin"
    }

    private fun interpolate(
        template: String,
        channel: ChannelEntity,
        startSec: Long,
        endSec: Long,
        nowSec: Long,
    ): String {
        val durationMin = ((endSec - startSec) / 60).coerceAtLeast(1)
        val startInstant = Instant.ofEpochSecond(startSec)
        val utcDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        val utcTime = DateTimeFormatter.ofPattern("HH-mm").withZone(ZoneOffset.UTC)

        var result = template
        val replacements = linkedMapOf(
            "{utc}" to startSec.toString(),
            "{utcstart}" to startSec.toString(),
            "{start}" to startSec.toString(),
            "{utcend}" to endSec.toString(),
            "{end}" to endSec.toString(),
            "{lutc}" to nowSec.toString(),
            "{duration}" to durationMin.toString(),
            "{Y}" to utcDate.format(startInstant).substring(0, 4),
            "{m}" to utcDate.format(startInstant).substring(5, 7),
            "{d}" to utcDate.format(startInstant).substring(8, 10),
            "{H}" to utcTime.format(startInstant).substring(0, 2),
            "{M}" to utcTime.format(startInstant).substring(3, 5),
            "{catchup-id}" to channel.remoteId,
        )
        for ((token, value) in replacements) {
            result = result.replace(token, value)
            // ${…} variant used by some generators
            result = result.replace("\$" + token, value)
        }
        return result
    }
}
