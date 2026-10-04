package com.iptvplayer.app

import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.remote.Base64Codec
import com.iptvplayer.app.data.remote.XtreamClient
import com.iptvplayer.app.player.Catchup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    private fun channel(
        url: String,
        catchupMode: String? = null,
        catchupSource: String? = null,
        available: Boolean = true,
    ) = ChannelEntity(
        uid = "1:LIVE:x",
        playlistId = 1,
        kind = "LIVE",
        remoteId = "x",
        name = "Test",
        url = url,
        catchupMode = catchupMode,
        catchupSource = catchupSource,
        catchupAvailable = available,
    )

    @Test
    fun unavailableReturnsNull() {
        assertNull(Catchup.buildUrl(channel("http://s/1", available = false), 100, 200, 150))
    }

    @Test
    fun appendModeAddsUtcParams() {
        val url = Catchup.buildUrl(channel("http://s/live?token=1", "append"), 100, 200, 150)
        assertEquals("http://s/live?token=1&utc=100&utcend=200", url)
    }

    @Test
    fun defaultModeAddsUtcParams() {
        val url = Catchup.buildUrl(channel("http://s/live", "default"), 100, 200, 150)
        assertEquals("http://s/live?utc=100&utcend=200", url)
    }

    @Test
    fun flussonicModeAddsTimeshift() {
        val url = Catchup.buildUrl(channel("http://s/live", "flussonic"), 90, 150, 150)
        assertNotNull(url)
        assertTrue(url!!.contains("timeshift=1"))
    }

    @Test
    fun xtreamFallbackFormat() {
        val ch = channel("http://s:8080/live/user/pass/123.m3u8")
        val url = Catchup.buildUrl(ch, 1767480000L, 1767483600L, 1767483600L)
        assertNotNull(url)
        assertTrue(url!!.startsWith("http://s:8080/live/user/pass/123.m3u8?start=2026-"))
        assertTrue(url.contains("duration=60"))
    }

    @Test
    fun templateInterpolation() {
        val ch = channel(
            url = "http://s/live",
            catchupSource = "http://s/archive/{utc}/{utcend}/{duration}/{lutc}.m3u8",
        )
        val url = Catchup.buildUrl(ch, 1000, 1600, 2000)
        assertEquals("http://s/archive/1000/1600/10/2000.m3u8", url)
    }

    @Test
    fun dollarTemplateInterpolation() {
        val ch = channel(
            url = "http://s/live",
            catchupSource = "http://s/a/\${utc}-\${utcend}.ts",
        )
        val url = Catchup.buildUrl(ch, 5, 6, 7)
        assertEquals("http://s/a/5-6.ts", url)
    }
}

class XtreamUrlsTest {

    @Test
    fun normalizeBaseUrl() {
        assertEquals("http://host:8080", XtreamClient.normalizeBaseUrl("host:8080"))
        assertEquals("http://host:8080", XtreamClient.normalizeBaseUrl("http://host:8080/"))
        assertEquals("https://host", XtreamClient.normalizeBaseUrl("https://host"))
    }

    @Test
    fun streamUrls() {
        val acc = XtreamClient.XcAccount("http://s:8080", "user", "pass")
        assertEquals(
            "http://s:8080/live/user/pass/42.m3u8",
            XtreamClient.liveStreamUrl(acc, 42, hls = true),
        )
        assertEquals(
            "http://s:8080/live/user/pass/42.ts",
            XtreamClient.liveStreamUrl(acc, 42, hls = false),
        )
        assertEquals(
            "http://s:8080/movie/user/pass/7.mp4",
            XtreamClient.movieUrl(acc, 7, "mp4"),
        )
        assertEquals(
            "http://s:8080/series/user/pass/9.mkv",
            XtreamClient.seriesEpisodeUrl(acc, 9, "mkv"),
        )
        assertEquals(
            "http://s:8080/xmltv.php?username=user&password=pass",
            XtreamClient.xmltvUrl(acc),
        )
    }
}

class Base64CodecTest {

    @Test
    fun decodesUtf8() {
        assertEquals("Привет", Base64Codec.decodeToString("0J/RgNC40LLQtdGCIQ=="))
        assertEquals("News", Base64Codec.decodeToString("TmV3cw=="))
    }

    @Test
    fun leavesInvalidInputUnchanged() {
        assertEquals("plain text", Base64Codec.decodeToString("plain text"))
        assertEquals("abc!@", Base64Codec.decodeToString("abc!@"))
    }
}
