package com.iptvplayer.app

import com.iptvplayer.app.player.MediaItems
import com.iptvplayer.app.player.StreamSniffer
import com.iptvplayer.app.player.StreamSniffer.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSnifferTest {

    // ------------------------------------------------------------ транспорты

    @Test
    fun udpIsMulticastTs() {
        val r = StreamSniffer.sniff("udp://@239.1.2.3:1234")
        assertEquals(StreamType.UDP, r.type)
        assertTrue(r.isMulticast)
        assertTrue(r.requiresCustomDataSource)
        assertFalse(r.isAdaptive)
        assertEquals("video/mp2t", r.mimeType)
        assertNull(r.containerExt)
    }

    @Test
    fun rtpIsMulticastToo() {
        val r = StreamSniffer.sniff("rtp://233.0.0.1:5000")
        assertEquals(StreamType.RTP, r.type)
        assertTrue(r.isMulticast)
        assertTrue(r.requiresCustomDataSource)
    }

    @Test
    fun rtmpFamilyNeedsCustomDataSource() {
        for (url in listOf(
            "rtmp://live.example.com/app/stream",
            "rtmps://live.example.com/app/stream",
            "rtmpe://live.example.com/app/stream",
        )) {
            val r = StreamSniffer.sniff(url)
            assertEquals(url, StreamType.RTMP, r.type)
            assertTrue(url, r.requiresCustomDataSource)
            assertFalse(url, r.isMulticast)
        }
    }

    @Test
    fun rtspDetected() {
        val r = StreamSniffer.sniff("rtsp://camera.local:554/h264/ch1/main/av_stream")
        assertEquals(StreamType.RTSP, r.type)
        assertTrue(r.requiresCustomDataSource)
        assertNull(r.mimeType)
    }

    @Test
    fun localSchemes() {
        val file = StreamSniffer.sniff("file:///sdcard/Movies/clip.mp4")
        assertEquals(StreamType.LOCAL, file.type)
        assertEquals("mp4", file.containerExt)
        assertEquals(
            StreamType.LOCAL,
            StreamSniffer.sniff("content://media/external/123").type,
        )
    }

    // ----------------------------------------------------------- расширения

    @Test
    fun hlsClassicAndTokenized() {
        for (url in listOf(
            "http://cdn.example.com/live/master.m3u8",
            "https://user:pass@cdn.example.com:8080/hls/CHANNEL.M3U8?token=abc&ttl=9#frag",
        )) {
            val r = StreamSniffer.sniff(url)
            assertEquals(url, StreamType.HLS, r.type)
            assertTrue(url, r.isAdaptive)
            assertEquals(url, "application/vnd.apple.mpegurl", r.mimeType)
            assertEquals(url, "m3u8", r.containerExt)
        }
        assertEquals(StreamType.HLS, StreamSniffer.sniff("http://x/list.m3u").type)
    }

    @Test
    fun dashManifests() {
        val r = StreamSniffer.sniff("https://vod.example.com/movie/manifest.mpd")
        assertEquals(StreamType.DASH, r.type)
        assertTrue(r.isAdaptive)
        assertEquals("application/dash+xml", r.mimeType)
    }

    @Test
    fun rawMpegTsStreams() {
        for (url in listOf(
            "http://xtream.example.com:8080/user/pass/123.ts",
            "http://x/feed.mpegts",
        )) {
            val r = StreamSniffer.sniff(url)
            assertEquals(url, StreamType.PROGRESSIVE_TS, r.type)
            assertEquals(url, "video/mp2t", r.mimeType)
            assertFalse(url, r.isAdaptive)
        }
    }

    @Test
    fun progressiveContainers() {
        assertEquals("video/mp4", StreamSniffer.mimeTypeFor("http://x/film.mp4"))
        assertEquals("video/x-matroska", StreamSniffer.mimeTypeFor("http://x/film.mkv"))
        assertEquals("video/avi", StreamSniffer.mimeTypeFor("http://x/film.avi?dl=1"))
        assertEquals(
            StreamType.PROGRESSIVE,
            StreamSniffer.sniff("http://x/film.webm").type,
        )
    }

    @Test
    fun audioFilesGetAudioMimes() {
        for ((ext, mime) in mapOf(
            "mp3" to "audio/mpeg",
            "aac" to "audio/aac",
            "m4a" to "audio/mp4",
            "flac" to "audio/flac",
            "ogg" to "audio/ogg",
        )) {
            val r = StreamSniffer.sniff("http://radio.example.com/stream/file.$ext")
            assertEquals(ext, StreamType.PROGRESSIVE, r.type)
            assertEquals(ext, mime, r.mimeType)
        }
    }

    // -------------------------------------------------------------- fallback

    @Test
    fun extensionlessHttpNeedsContentSniffing() {
        for (url in listOf(
            "http://xtream.example.com:8080/user/pass/123",
            "http://x/watch?v=abc",
            "http://x/stream",
        )) {
            val r = StreamSniffer.sniff(url)
            assertEquals(url, StreamType.UNKNOWN, r.type)
            assertNull(url, r.mimeType)
            assertNull(url, r.containerExt)
            assertTrue(url, r.needsContentSniffing)
        }
    }

    @Test
    fun unknownExtensionKeepsExtButNeedsSniffing() {
        val r = StreamSniffer.sniff("http://x/stream.php?id=1")
        assertEquals(StreamType.UNKNOWN, r.type)
        assertEquals("php", r.containerExt)
        assertNull(r.mimeType)
        assertTrue(r.needsContentSniffing)
    }

    @Test
    fun garbageIsSafe() {
        for (url in listOf("", "   ", "not a url at all", "://broken")) {
            val r = StreamSniffer.sniff(url)
            assertEquals(url, StreamType.UNKNOWN, r.type)
            assertNull(url, r.mimeType)
        }
    }

    @Test
    fun helpers() {
        assertTrue(StreamSniffer.isAdaptive("http://x/live.m3u8"))
        assertTrue(StreamSniffer.isAdaptive("http://x/live.mpd"))
        assertFalse(StreamSniffer.isAdaptive("http://x/live.ts"))
        assertEquals("ts", StreamSniffer.extensionOf("HTTP://X/LIVE.TS?t=1"))
        assertNull(StreamSniffer.extensionOf("http://x/noext?file=video.mp4"))
        assertEquals("channel.ts", StreamSniffer.sniff("channel.ts").url)
        assertEquals(StreamType.PROGRESSIVE_TS, StreamSniffer.sniff("channel.ts").type)
        assertEquals("udp", StreamSniffer.schemeOf("UDP://239.0.0.1:1234"))
        assertNull(StreamSniffer.schemeOf("no-scheme-here"))
    }

    // -------------------------------------- паритет с плеерным классом

    @Test
    fun mimeTableIsBackwardsCompatibleWithMediaItems() {
        // StreamSniffer must not regress the urls MediaItems.guessMime already
        // classifies (same mime strings, null where null).
        for (url in listOf(
            "http://x/a.m3u8", "http://x/a.mpd", "http://x/a.ts",
            "http://x/a.mpegts", "http://x/a.mp4", "http://x/a.m4v",
            "http://x/a.webm", "http://x/a.mkv", "http://x/a.avi",
            "http://x/a.flv", "http://x/a.mov", "http://x/a.wmv",
            "http://x/a.mp3", "http://x/a.m4a", "http://x/a.aac",
            "http://x/no-extension",
        )) {
            assertEquals(url, MediaItems.guessMime(url), StreamSniffer.mimeTypeFor(url))
        }
    }
}
