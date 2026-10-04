package com.iptvplayer.app.data.epg

import com.iptvplayer.app.data.model.NowNext
import com.iptvplayer.app.data.model.ProgrammeInfo

/**
 * In-memory EPG index over parsed XMLTV data: channel lookup by id,
 * display-name resolution, now/next queries, schedule windows and title
 * search. Build it from Room rows ([com.iptvplayer.app.data.db.ProgrammeEntity])
 * or parser results, then run the queries off the UI thread.
 *
 * Pure JVM logic (no Room / Android dependencies) — covered by EpgCatalogTest.
 */
class EpgCatalog(
    channels: List<Channel> = emptyList(),
    programmes: List<Programme> = emptyList(),
) {

    /** One `<channel>` entry of an XMLTV document. */
    data class Channel(
        val id: String,
        val names: List<String> = emptyList(),
        val icon: String? = null,
    ) {
        /** Primary display name — the id itself when no names were given. */
        val displayName: String get() = names.firstOrNull() ?: id
    }

    /** One `<programme>` entry of an XMLTV document (epoch seconds). */
    data class Programme(
        val channelId: String,
        val start: Long,
        val stop: Long,
        val title: String,
        val description: String? = null,
        val category: String? = null,
        val icon: String? = null,
    ) {
        val durationSec: Long get() = stop - start

        /** True when [atSec] falls into [start, stop). */
        fun contains(atSec: Long): Boolean = atSec in start until stop

        fun toInfo(): ProgrammeInfo = ProgrammeInfo(
            start = start,
            stop = stop,
            title = title,
            description = description,
        )
    }

    private val channelsById: Map<String, Channel> =
        channels.filter { it.id.isNotBlank() }.associateBy { it.id }

    // epgId -> programmes sorted by start. Rows with blank ids or stop <=
    // start are dropped; exact duplicates (same id/start/title) collapse.
    private val programmesById: Map<String, List<Programme>> = programmes
        .filter { it.channelId.isNotBlank() && it.stop > it.start }
        .distinctBy { it.channelId to it.start to it.title }
        .groupBy { it.channelId }
        .mapValues { (_, list) -> list.sortedBy { it.start } }

    // normalized display-name -> epgId (name fallback matching); the first
    // channel wins when several channels share a name.
    private val nameIndex: Map<String, String> = run {
        val index = LinkedHashMap<String, String>()
        for (ch in channels) {
            if (ch.id.isBlank()) continue
            for (name in ch.names) {
                val key = normalizeName(name)
                if (key.isNotEmpty()) index.putIfAbsent(key, ch.id)
            }
        }
        index
    }

    /** Number of channels the catalog knows about. */
    val channelCount: Int get() = channelsById.size

    /** Total number of programme rows (after dedup / validation). */
    val programmeCount: Int get() = programmesById.values.sumOf { it.size }

    /** Channel metadata by its canonical EPG id. */
    fun channel(epgId: String): Channel? = channelsById[epgId]

    /** Ids of all channels that actually have programmes. */
    val channelIdsWithProgrammes: Set<String> get() = programmesById.keys

    /**
     * Resolves an EPG id for a playlist channel. Explicit ids ([tvgId],
     * [epgChannelId]) win, but only when the catalog actually knows them
     * (either as a channel entry or as programme data); an unknown id never
     * shadows a good display-name match. The name is matched with
     * [normalizeName] rules (case, ё, separators, quality suffixes).
     * Null when nothing matches.
     */
    fun resolveEpgId(
        tvgId: String? = null,
        epgChannelId: String? = null,
        channelName: String? = null,
    ): String? {
        for (id in listOf(tvgId, epgChannelId)) {
            if (!id.isNullOrBlank() &&
                (channelsById.containsKey(id) || programmesById.containsKey(id))
            ) {
                return id
            }
        }
        if (channelName.isNullOrBlank()) return null
        return nameIndex[normalizeName(channelName)]
    }

    /** Programme on air at [atSec] (start inclusive, stop exclusive). */
    fun currentProgramme(epgId: String, atSec: Long): Programme? =
        programmesById[epgId]?.lastOrNull { it.contains(atSec) }

    /** First programme starting strictly after [atSec]. */
    fun nextProgramme(epgId: String, atSec: Long): Programme? =
        programmesById[epgId]?.firstOrNull { it.start > atSec }

    /** Now/next pair for the UI ("сейчас / далее"). */
    fun nowNext(epgId: String, atSec: Long): NowNext = NowNext(
        now = currentProgramme(epgId, atSec)?.toInfo(),
        next = nextProgramme(epgId, atSec)?.toInfo(),
    )

    /** Progress (0.0–1.0) of the current programme, null when off air. */
    fun progress(epgId: String, atSec: Long): Float? =
        currentProgramme(epgId, atSec)?.let {
            ((atSec - it.start).toFloat() / it.durationSec).coerceIn(0f, 1f)
        }

    /** Programmes overlapping [fromSec, toSec), sorted by start. */
    fun schedule(epgId: String, fromSec: Long, toSec: Long): List<Programme> {
        if (toSec <= fromSec) return emptyList()
        return programmesById[epgId].orEmpty()
            .filter { it.start < toSec && it.stop > fromSec }
    }

    /**
     * Title search across the whole catalog. The query and titles are
     * compared via [normalizeText]; queries shorter than 2 normalized chars
     * match nothing.
     *
     * @param fromSec keep only programmes ending at/after this epoch second
     * @param limit max rows, sorted by start time ascending
     */
    fun searchTitles(query: String, fromSec: Long = 0L, limit: Int = 100): List<Programme> {
        val q = normalizeText(query)
        if (q.length < 2 || limit <= 0) return emptyList()
        return programmesById.values.asSequence()
            .flatten()
            .filter { it.stop >= fromSec }
            .filter { normalizeText(it.title).contains(q) }
            .sortedBy { it.start }
            .take(limit)
            .toList()
    }

    companion object {
        /** Trailing display-name tokens that do not affect matching. */
        private val QUALITY_TOKENS = setOf(
            "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "hdr", "h265", "h264",
        )

        private val SEPARATORS = Regex("[\\s\\-_.,:;/\\\\()\\[\\]{}|]+")

        /**
         * Normalizes a channel display name for matching: lowercase, ё→е,
         * runs of separators collapse to single spaces, trailing quality
         * tokens ("HD", "4K", "HEVC", …) are dropped — so
         * "Первый Канал (HD)" equals "первый канал". The result never
         * collapses to an empty string.
         */
        fun normalizeName(name: String): String {
            val base = normalizeText(name)
            if (base.isEmpty()) return base
            val tokens = base.split(' ')
            val stripped = tokens.dropLastWhile { it in QUALITY_TOKENS }
            return if (stripped.isEmpty()) base else stripped.joinToString(" ")
        }

        /** Normalizes arbitrary text: lowercase, ё→е, separators → space. */
        fun normalizeText(text: String): String =
            SEPARATORS.replace(text.lowercase().replace('ё', 'е'), " ").trim()
    }
}
