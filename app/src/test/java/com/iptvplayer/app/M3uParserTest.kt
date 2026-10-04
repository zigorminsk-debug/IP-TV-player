package com.iptvplayer.app

import com.iptvplayer.app.data.remote.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="http://epg.example.com/guide.xml"
        #EXTINF:-1 tvg-id="channel.one" tvg-name="Channel One" tvg-logo="http://logo/1.png" group-title="News" catchup="default",Первый канал
        http://stream.example.com/one.m3u8
        #EXTINF:-1 tvg-id="channel.two" group-title="News",Second Channel
        #EXTVLCOPT:http-user-agent=CustomUA/1.0
        #EXTVLCOPT:http-referrer=http://ref.example.com
        http://stream.example.com/two.ts
        #EXTINF:-1,Plain Entry
        #EXTGRP:Movies
        http://stream.example.com/plain.mp4
        #EXTINF:-1 catchup-source="http://arch/{utc}-{utcend}.m3u8" catchup="append",Archive Channel
        http://stream.example.com/live.m3u8
    """.trimIndent()

    @Test
    fun parsesHeaderAttributes() {
        val result = M3uParser.parse(ByteArrayInputStream(sample.toByteArray()))
        assertEquals("http://epg.example.com/guide.xml", result.headerAttrs["url-tvg"])
    }

    @Test
    fun parsesEntries() {
        val result = M3uParser.parse(ByteArrayInputStream(sample.toByteArray()))
        assertEquals(4, result.entries.size)

        val first = result.entries[0]
        assertEquals("Первый канал", first.name)
        assertEquals("http://stream.example.com/one.m3u8", first.url)
        assertEquals("channel.one", first.attrs["tvg-id"])
        assertEquals("News", first.attrs["group-title"])
        assertEquals("default", first.attrs["catchup"])

        val second = result.entries[1]
        assertEquals("CustomUA/1.0", second.httpUserAgent)
        assertEquals("http://ref.example.com", second.httpReferrer)
        assertEquals("http://stream.example.com/two.ts", second.url)

        val third = result.entries[2]
        assertEquals("Plain Entry", third.name)
        assertEquals("Movies", third.extGrp)

        val fourth = result.entries[3]
        assertEquals(
            "http://arch/{utc}-{utcend}.m3u8",
            fourth.attrs["catchup-source"],
        )
        assertEquals("append", fourth.attrs["catchup"])
    }

    @Test
    fun ignoresUnknownDirectives() {
        val input = """
            #EXTM3U
            #KODIPROP:inputstream=ffmpeg
            #EXTINF:-1,Test
            http://stream/test
            #EXT-X-ENDLIST
        """.trimIndent()
        val result = M3uParser.parse(ByteArrayInputStream(input.toByteArray()))
        assertEquals(1, result.entries.size)
        assertEquals("Test", result.entries[0].name)
    }

    @Test
    fun handlesEmptyInput() {
        val result = M3uParser.parse(ByteArrayInputStream("#EXTM3U".toByteArray()))
        assertTrue(result.entries.isEmpty())
        assertNull(null)
        assertNotNull(result)
    }
}
