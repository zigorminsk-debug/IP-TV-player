package com.iptvplayer.app

import com.iptvplayer.app.data.remote.XmlTvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class XmlTvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="ch1">
            <display-name>Первый</display-name>
            <display-name>Channel One</display-name>
            <icon src="http://logo/ch1.png"/>
          </channel>
          <channel id="ch2">
            <display-name>Second</display-name>
          </channel>
          <programme start="20260104120000 +0300" stop="20260104130000 +0300" channel="ch1">
            <title>Новости</title>
            <desc>Итоги дня</desc>
            <category>News</category>
          </programme>
          <programme start="20260104130000 +0300" stop="20260104140000 +0300" channel="ch1">
            <title>Фильм</title>
          </programme>
          <programme start="20260104120000 +0000" stop="20260104123000 +0000" channel="ch2">
            <title>Too old programme</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parsesChannelsAndProgrammes() {
        val result = XmlTvParser.parse(
            ByteArrayInputStream(xml.toByteArray()),
            keepFromSec = 0,
            keepUntilSec = Long.MAX_VALUE,
        )
        assertEquals(2, result.channels.size)
        val ch1 = result.channels.first { it.id == "ch1" }
        assertEquals("Первый", ch1.names.first())
        assertEquals(2, ch1.names.size)
        assertEquals("http://logo/ch1.png", ch1.icon)

        assertEquals(3, result.programmes.size)
        val news = result.programmes.first { it.title == "Новости" }
        assertEquals("ch1", news.channelId)
        assertEquals("Итоги дня", news.desc)
        assertEquals("News", news.category)
    }

    @Test
    fun windowFilterDropsOldProgrammes() {
        val result = XmlTvParser.parse(
            ByteArrayInputStream(xml.toByteArray()),
            keepFromSec = 1767225600L, // 2026-01-01 00:00 UTC
            keepUntilSec = 1767312000L, // 2026-01-02 00:00 UTC
        )
        // all programmes are outside the window -> dropped
        assertTrue(result.programmes.isEmpty())
    }

    @Test
    fun parseDateTimeFormats() {
        assertEquals(
            1767528000L, // 2026-01-04 12:00:00 UTC
            XmlTvParser.parseDateTime("20260104120000 +0000"),
        )
        // +0300 shifts the epoch back by 3 hours
        assertEquals(
            1767528000L - 3 * 3600,
            XmlTvParser.parseDateTime("20260104120000 +0300"),
        )
        assertEquals(
            1767528000L,
            XmlTvParser.parseDateTime("20260104120000"),
        )
        assertEquals(
            1767528000L,
            XmlTvParser.parseDateTime("202601041200"),
        )
        assertNull(XmlTvParser.parseDateTime(null))
        assertNull(XmlTvParser.parseDateTime("garbage"))
    }
}
