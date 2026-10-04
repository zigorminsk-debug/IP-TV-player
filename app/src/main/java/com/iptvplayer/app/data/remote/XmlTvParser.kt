package com.iptvplayer.app.data.remote

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** Streaming XMLTV (EPG) parser with a window filter to keep memory bounded. */
object XmlTvParser {

    class EpgChannel(
        val id: String,
        val names: List<String>,
        val icon: String?,
    )

    class EpgProgramme(
        val channelId: String,
        val start: Long,
        val stop: Long,
        val title: String,
        val desc: String?,
        val category: String?,
        val icon: String?,
    )

    class Result(
        val channels: List<EpgChannel>,
        val programmes: List<EpgProgramme>,
    )

    /** Opens [input], transparently handling gzip-compressed XMLTV files. */
    fun openPossiblyGzip(input: InputStream): InputStream {
        input.mark(2)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(input) else input
    }

    /**
     * Parses an XMLTV document.
     *
     * @param keepFromSec drop programmes that ended before this epoch second
     * @param keepUntilSec drop programmes that start after this epoch second
     */
    fun parse(
        input: InputStream,
        keepFromSec: Long? = null,
        keepUntilSec: Long? = null,
    ): Result {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(openPossiblyGzip(input).reader(Charsets.UTF_8))

        val channels = mutableListOf<EpgChannel>()
        val programmes = mutableListOf<EpgProgramme>()

        var inChannel = false
        var inProgramme = false
        var capture: String? = null // name of the currently captured tag

        var chId = ""
        var chNames = mutableListOf<String>()
        var chIcon: String? = null

        var prChannel = ""
        var prStart = 0L
        var prStop = 0L
        var prTitle = ""
        var prDesc: String? = null
        var prCategory: String? = null
        var prIcon: String? = null

        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        chId = parser.getAttributeValue(null, "id") ?: ""
                        chNames = mutableListOf()
                        chIcon = null
                    }
                    "programme" -> {
                        inProgramme = true
                        prChannel = parser.getAttributeValue(null, "channel") ?: ""
                        prStart = parseDateTime(parser.getAttributeValue(null, "start")) ?: 0L
                        prStop = parseDateTime(parser.getAttributeValue(null, "stop")) ?: 0L
                        prTitle = ""
                        prDesc = null
                        prCategory = null
                        prIcon = null
                    }
                    "display-name" -> if (inChannel) capture = "display-name"
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")
                        if (inChannel) chIcon = src
                        if (inProgramme) prIcon = src
                    }
                    "title" -> if (inProgramme) capture = "title"
                    "desc" -> if (inProgramme) capture = "desc"
                    "category" -> if (inProgramme) capture = "category"
                }

                XmlPullParser.TEXT -> if (capture != null) text.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = false
                        if (chId.isNotEmpty()) {
                            channels += EpgChannel(chId, chNames.toList(), chIcon)
                        }
                    }
                    "programme" -> {
                        inProgramme = false
                        if (prChannel.isNotEmpty() && prStart in 1 until prStop) {
                            val tooOld = keepFromSec != null && prStop < keepFromSec
                            val tooNew = keepUntilSec != null && prStart > keepUntilSec
                            if (!tooOld && !tooNew) {
                                programmes += EpgProgramme(
                                    channelId = prChannel,
                                    start = prStart,
                                    stop = prStop,
                                    title = prTitle,
                                    desc = prDesc,
                                    category = prCategory,
                                    icon = prIcon,
                                )
                            }
                        }
                    }
                    "display-name" -> if (capture == "display-name") {
                        chNames += text.toString().trim()
                        capture = null; text = StringBuilder()
                    }
                    "title" -> if (capture == "title") {
                        prTitle = text.toString().trim(); capture = null; text = StringBuilder()
                    }
                    "desc" -> if (capture == "desc") {
                        prDesc = text.toString().trim(); capture = null; text = StringBuilder()
                    }
                    "category" -> if (capture == "category") {
                        prCategory = text.toString().trim(); capture = null; text = StringBuilder()
                    }
                }
            }
            event = parser.next()
        }
        return Result(channels, programmes)
    }

    /**
     * Parses XMLTV datetime: "20260104123000 +0300", "20260104123000",
     * "202601041230" etc. Returns epoch seconds.
     */
    fun parseDateTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        if (value.length < 10) return null
        return try {
        val digits = StringBuilder()
        for (c in value) {
            if (c.isDigit()) digits.append(c) else break
        }
        // zone tail must be cut from the ORIGINAL value before padding
        val tail = value.substring(minOf(digits.length, value.length))
        while (digits.length < 14) digits.append('0')
        val local = LocalDateTime.parse(
            digits.toString(),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
        )
        val zone = parseZone(tail)
        local.toInstant(zone).toEpochMilli() / 1000
        } catch (e: Exception) {
            null
        }
    }

    private fun parseZone(tail: String): ZoneOffset {
        val cleaned = tail.trim()
        if (cleaned.isEmpty()) return ZoneOffset.UTC
        // Accept "+0300", "+03:00", "-05", "Z"
        val m = Regex("([+-])(\\d{1,2})(?::?(\\d{2}))?").find(cleaned) ?: return ZoneOffset.UTC
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val hours = m.groupValues[2].toIntOrNull() ?: 0
        val minutes = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return try {
            ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
        } catch (e: Exception) {
            ZoneOffset.UTC
        }
    }
}
