package com.iptvplayer.app.data.remote

import java.io.InputStream

/** Streaming M3U/M3U8 playlist parser. */
object M3uParser {

    class M3uEntry(
        val name: String,
        val url: String,
        val attrs: Map<String, String>,
        val httpUserAgent: String?,
        val httpReferrer: String?,
        val extGrp: String?,
    )

    class M3uPlaylist(
        /** Attributes of the #EXTM3U header (url-tvg, x-tvg-url, …). */
        val headerAttrs: Map<String, String>,
        val entries: List<M3uEntry>,
    )

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    fun parse(input: InputStream): M3uPlaylist {
        val header = mutableMapOf<String, String>()
        val entries = mutableListOf<M3uEntry>()

        var pendingName: String? = null
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingUa: String? = null
        var pendingReferrer: String? = null
        var pendingGrp: String? = null

        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim().removePrefix("\uFEFF")
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) ->
                        header.putAll(parseAttrs(line))

                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val commaIdx = line.indexOf(',')
                        pendingName = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
                        pendingAttrs =
                            parseAttrs(if (commaIdx >= 0) line.substring(0, commaIdx) else line)
                    }

                    line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                        val value = line.substringAfter(':', "")
                        when {
                            value.startsWith("http-user-agent", ignoreCase = true) ->
                                pendingUa = value.substringAfter('=').trim()
                            value.startsWith("http-referrer", ignoreCase = true) ->
                                pendingReferrer = value.substringAfter('=').trim()
                        }
                    }

                    line.startsWith("#EXTGRP", ignoreCase = true) ->
                        pendingGrp = line.substringAfter(':', "").trim()

                    line.startsWith("#", ignoreCase = true) -> {
                        // #KODIPROP, #EXTHTTP and other exotic directives are ignored.
                    }

                    pendingName != null -> {
                        entries += M3uEntry(
                            name = pendingName ?: "",
                            url = line,
                            attrs = pendingAttrs,
                            httpUserAgent = pendingUa,
                            httpReferrer = pendingReferrer,
                            extGrp = pendingGrp ?: pendingAttrs["group-title"],
                        )
                        pendingName = null
                        pendingAttrs = emptyMap()
                        pendingUa = null
                        pendingReferrer = null
                        pendingGrp = null
                    }
                }
            }
        }
        return M3uPlaylist(header, entries)
    }

    private fun parseAttrs(source: String): Map<String, String> =
        attrRegex.findAll(source).associate { m ->
            m.groupValues[1].lowercase() to m.groupValues[2]
        }
}
