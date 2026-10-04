package com.iptvplayer.app.player

/**
 * Classifies stream urls before they are handed over to ExoPlayer (or to an
 * external player): transport scheme, container/manifest kind, guessed mime
 * type and a few playback-relevant flags.
 *
 * The streaming counterpart of [MediaItems.guessMime] — its mime table is a
 * strict superset and backwards compatible (the parity is pinned by
 * StreamSnifferTest). Useful for picking a DataSource up front and for
 * deciding whether the content must be sniffed by the player itself.
 *
 * Pure JVM logic — no Android dependencies, covered by unit tests.
 */
object StreamSniffer {

    /** High-level kind of the resource behind a stream url. */
    enum class StreamType {
        HLS,            // Apple HLS playlist (.m3u8 / .m3u)
        DASH,           // MPEG-DASH manifest (.mpd)
        PROGRESSIVE_TS, // raw MPEG-TS over http(s) — most Xtream live streams
        PROGRESSIVE,    // progressive media file, incl. audio-only (mp4, mkv, mp3, …)
        RTMP,           // rtmp/rtmps/rtmpe/… — media3 rtmp extension
        UDP,            // udp:// — multicast MPEG-TS (UdpDataSource)
        RTP,            // rtp:// — multicast MPEG-TS over RTP (UdpDataSource)
        RTSP,           // rtsp:// — ExoPlayer RtspMediaSource
        LOCAL,          // file:// / content:// / asset — on-device media
        UNKNOWN,        // no reliable hint — the player must sniff the content
    }

    /** Classification of one url, see [sniff]. */
    data class SniffResult(
        val url: String,
        val type: StreamType,
        /** Guessed content mime type, null = "let the player sniff". */
        val mimeType: String?,
        /** Lowercase container/manifest extension ("m3u8", "ts", …) or null. */
        val containerExt: String?,
    ) {
        /** HLS/DASH playlists — adaptive-bitrate capable. */
        val isAdaptive: Boolean
            get() = type == StreamType.HLS || type == StreamType.DASH

        /** udp:// or rtp:// multicast — playback requires UdpDataSource. */
        val isMulticast: Boolean
            get() = type == StreamType.UDP || type == StreamType.RTP

        /** Not servable by the plain OkHttp/DefaultDataSource pair. */
        val requiresCustomDataSource: Boolean
            get() = type == StreamType.UDP || type == StreamType.RTP ||
                type == StreamType.RTMP || type == StreamType.RTSP

        /** No reliable container hint — ExoPlayer has to sniff the stream. */
        val needsContentSniffing: Boolean
            get() = type == StreamType.UNKNOWN
    }

    // Mime strings intentionally mirror androidx.media3.common.MimeTypes so
    // the classification can feed MediaItems without conversion. Note the
    // media3 value for HLS is "application/x-mpegURL" (APPLICATION_M3U8),
    // not the canonical Apple name — keep them identical.
    private const val MIME_HLS = "application/x-mpegURL"
    private const val MIME_DASH = "application/dash+xml"
    private const val MIME_TS = "video/mp2t"

    /** extension (lowercase) -> (type, mime) for http(s) / scheme-less urls. */
    private val EXTENSION_TABLE: Map<String, Pair<StreamType, String>> = mapOf(
        // playlists & manifests
        "m3u8" to (StreamType.HLS to MIME_HLS),
        "m3u" to (StreamType.HLS to MIME_HLS),
        "mpd" to (StreamType.DASH to MIME_DASH),
        // raw mpeg-ts
        "ts" to (StreamType.PROGRESSIVE_TS to MIME_TS),
        "mts" to (StreamType.PROGRESSIVE_TS to MIME_TS),
        "m2ts" to (StreamType.PROGRESSIVE_TS to MIME_TS),
        "mpegts" to (StreamType.PROGRESSIVE_TS to MIME_TS),
        // progressive video
        "mp4" to (StreamType.PROGRESSIVE to "video/mp4"),
        "m4v" to (StreamType.PROGRESSIVE to "video/mp4"),
        "webm" to (StreamType.PROGRESSIVE to "video/webm"),
        "mkv" to (StreamType.PROGRESSIVE to "video/x-matroska"),
        "avi" to (StreamType.PROGRESSIVE to "video/avi"),
        "flv" to (StreamType.PROGRESSIVE to "video/x-flv"),
        "mov" to (StreamType.PROGRESSIVE to "video/quicktime"),
        "wmv" to (StreamType.PROGRESSIVE to "video/x-ms-wmv"),
        // progressive audio
        "mp3" to (StreamType.PROGRESSIVE to "audio/mpeg"),
        "m4a" to (StreamType.PROGRESSIVE to "audio/mp4"),
        "aac" to (StreamType.PROGRESSIVE to "audio/aac"),
        "ogg" to (StreamType.PROGRESSIVE to "audio/ogg"),
        "oga" to (StreamType.PROGRESSIVE to "audio/ogg"),
        "flac" to (StreamType.PROGRESSIVE to "audio/flac"),
        "wav" to (StreamType.PROGRESSIVE to "audio/wav"),
        "opus" to (StreamType.PROGRESSIVE to "audio/opus"),
        "ac3" to (StreamType.PROGRESSIVE to "audio/ac3"),
        "eac3" to (StreamType.PROGRESSIVE to "audio/eac3"),
    )

    private val SCHEMES_RTMP = setOf("rtmp", "rtmps", "rtmpe", "rtmpt", "rtmfp")
    private val SCHEMES_LOCAL = setOf("file", "content", "asset")

    // "m2ts"/"mpegts" are the longest extensions we care about (6 chars).
    private val EXT_RE = Regex("[a-z0-9]{1,6}")

    /**
     * Classifies [url]. Never throws — garbage in, [StreamType.UNKNOWN] out.
     */
    fun sniff(url: String): SniffResult {
        val clean = url.trim()
        if (clean.isEmpty()) return SniffResult(clean, StreamType.UNKNOWN, null, null)

        when (schemeOf(clean)) {
            "udp" -> return SniffResult(clean, StreamType.UDP, MIME_TS, null)
            "rtp" -> return SniffResult(clean, StreamType.RTP, MIME_TS, null)
            "rtsp" -> return SniffResult(clean, StreamType.RTSP, null, null)
            in SCHEMES_RTMP -> return SniffResult(clean, StreamType.RTMP, null, null)
            in SCHEMES_LOCAL ->
                return SniffResult(clean, StreamType.LOCAL, null, extensionOf(clean))
        }

        // http(s), scheme-less paths and anything else: decide by extension.
        val ext = extensionOf(clean)
            ?: return SniffResult(clean, StreamType.UNKNOWN, null, null)
        val (type, mime) = EXTENSION_TABLE[ext]
            ?: return SniffResult(clean, StreamType.UNKNOWN, null, ext)
        return SniffResult(clean, type, mime, ext)
    }

    /** Convenience: guessed mime type of [url] (null = sniff by content). */
    fun mimeTypeFor(url: String): String? = sniff(url).mimeType

    /** Convenience: true for adaptive-bitrate playlists (HLS / DASH). */
    fun isAdaptive(url: String): Boolean = sniff(url).isAdaptive

    /**
     * Lowercase extension of the last path segment of [url], ignoring query
     * and fragment parts. Null when the segment has no dot or the suffix is
     * not a plain alnum token ("…/live", "…/watch?v=1").
     */
    fun extensionOf(url: String): String? {
        val clean = url.trim().substringBefore('#').substringBefore('?')
        val lastSegment = clean.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0 || dot == lastSegment.length - 1) return null
        val ext = lastSegment.substring(dot + 1).lowercase()
        return if (ext.matches(EXT_RE)) ext else null
    }

    /** Lowercase url scheme ("http", "udp", …) or null when absent/garbled. */
    fun schemeOf(url: String): String? {
        val idx = url.trim().indexOf(':')
        if (idx <= 0) return null
        val scheme = url.trim().substring(0, idx).lowercase()
        return if (scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) {
            scheme
        } else {
            null
        }
    }
}
