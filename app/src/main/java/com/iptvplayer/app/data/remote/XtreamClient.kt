package com.iptvplayer.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * Xtream Codes API client (player_api.php). The API is sloppy with types
 * (numbers as strings, nulls everywhere), so everything is parsed manually
 * through [JsonObject] helpers.
 */
class XtreamClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** Connection info for one Xtream playlist. */
    data class XcAccount(
        val serverUrl: String,
        val username: String,
        val password: String,
    ) {
        val apiUrl: String
            get() = apiUrl(serverUrl, username, password)
    }

    data class XcUser(
        val username: String,
        val status: String,
        val expiresAt: Long?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val serverUrl: String,
        val timezone: String?,
    )

    data class XcCategory(val id: String, val name: String)

    data class XcStream(
        val streamId: Long,
        val name: String,
        val logo: String?,
        val categoryId: String?,
        val epgChannelId: String?,
        val archive: Boolean,
        val archiveDurationMin: Int,
        val containerExt: String?,
        val plot: String?,
        val rating: Double?,
        val seriesId: Long?,
        val releaseDate: String?,
    )

    data class XcVodInfo(
        val name: String,
        val plot: String?,
        val poster: String?,
        val containerExt: String,
        val rating: Double?,
        val genre: String?,
        val year: String?,
        val director: String?,
        val cast: String?,
        val duration: String?,
    )

    data class XcEpisode(
        val id: Long,
        val title: String,
        val episodeNum: Int,
        val season: Int,
        val containerExt: String,
        val plot: String?,
        val poster: String?,
        val duration: String?,
    )

    data class XcSeriesInfo(
        val name: String,
        val plot: String?,
        val poster: String?,
        val genre: String?,
        val releaseDate: String?,
        val rating: Double?,
        val cast: String?,
        val director: String?,
        val seasons: Map<Int, List<XcEpisode>>,
    )

    data class XcEpgItem(
        val title: String,
        val description: String?,
        val start: Long,
        val stop: Long,
    )

    // ------------------------------------------------------------------ urls

    companion object {
        /** Normalizes "host:port", "http://host" etc. into a base url. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

        private fun enc(v: String): String =
            URLEncoder.encode(v, "UTF-8")

        fun apiUrl(server: String, username: String, password: String): String =
            "${normalizeBaseUrl(server)}/player_api.php" +
                "?username=${enc(username)}&password=${enc(password)}"

        fun liveStreamUrl(acc: XcAccount, streamId: Long, hls: Boolean): String =
            "${normalizeBaseUrl(acc.serverUrl)}/live/${enc(acc.username)}/${enc(acc.password)}" +
                "/$streamId.${if (hls) "m3u8" else "ts"}"

        fun movieUrl(acc: XcAccount, streamId: Long, ext: String): String =
            "${normalizeBaseUrl(acc.serverUrl)}/movie/${enc(acc.username)}/${enc(acc.password)}" +
                "/$streamId.${ext.ifEmpty { "mp4" }}"

        fun seriesEpisodeUrl(acc: XcAccount, episodeId: Long, ext: String): String =
            "${normalizeBaseUrl(acc.serverUrl)}/series/${enc(acc.username)}/${enc(acc.password)}" +
                "/$episodeId.${ext.ifEmpty { "mp4" }}"

        /** Full EPG (XMLTV) url for the account, when the panel provides one. */
        fun xmltvUrl(acc: XcAccount): String =
            "${normalizeBaseUrl(acc.serverUrl)}/xmltv.php" +
                "?username=${enc(acc.username)}&password=${enc(acc.password)}"
    }

    // -------------------------------------------------------------- helpers

    private fun JsonObject?.str(key: String): String? {
        val el = this?.get(key) ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.content
    }

    private fun JsonObject?.long(key: String): Long? {
        val s = str(key) ?: return null
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject?.int(key: String): Int? = long(key)?.toInt()

    private fun JsonObject?.double(key: String): Double? =
        str(key)?.let { it.toDoubleOrNull() }

    private fun JsonObject?.bool(key: String): Boolean {
        return when (val s = str(key)?.lowercase()) {
            "1", "true", "yes" -> true
            else -> false
        }
    }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun JsonObject?.array(key: String): List<JsonObject> =
        ((this?.get(key) as? JsonArray)?.filterIsInstance<JsonObject>()) ?: emptyList()

    private suspend fun call(acc: XcAccount, action: String?, vararg extra: Pair<String, String>): JsonElement? =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(acc.apiUrl)
                if (!action.isNullOrEmpty()) append("&action=").append(enc(action))
                for ((k, v) in extra) append("&").append(enc(k)).append('=').append(enc(v))
            }
            val body = Http.getString(http, url)
            if (body.isBlank()) return@withContext null
            val cleaned = body.trim().removePrefix("\uFEFF")
            return@withContext try {
                json.parseToJsonElement(cleaned)
            } catch (e: Exception) {
                null
            }
        }

    // ---------------------------------------------------------------- api

    /** Returns null when authentication fails or the server is unreachable. */
    suspend fun getUserInfo(acc: XcAccount): XcUser? {
        val root = call(acc, null) as? JsonObject ?: return null
        val info = root.obj("user_info") ?: return null
        val server = root.obj("server_info")
        if (info.int("auth") != 1) return null
        return XcUser(
            username = info.str("username") ?: acc.username,
            status = info.str("status") ?: "",
            expiresAt = info.long("exp_date"),
            maxConnections = info.int("max_connections"),
            activeConnections = info.int("active_cons"),
            serverUrl = server?.str("url")?.let { normalizeBaseUrl(it) } ?: acc.serverUrl,
            timezone = server?.str("timezone"),
        )
    }

    /** kind: live | vod_streams | series — returns category list. */
    suspend fun getCategories(acc: XcAccount, xcAction: String): List<XcCategory> {
        val arr = call(acc, xcAction) ?: return emptyList()
        val array = arr as? JsonArray
        return array?.filterIsInstance<JsonObject>()?.mapNotNull { o ->
            val id = o.str("category_id") ?: return@mapNotNull null
            XcCategory(id, o.str("category_name") ?: id)
        } ?: emptyList()
    }

    /** Fetches stream lists: get_live_streams / get_vod_streams / get_series. */
    suspend fun getStreams(acc: XcAccount, xcAction: String): List<XcStream> {
        val arr = call(acc, xcAction) ?: return emptyList()
        val array = arr as? JsonArray
        return array?.filterIsInstance<JsonObject>()?.mapNotNull { o ->
            val streamId = o.long("stream_id") ?: o.long("series_id") ?: return@mapNotNull null
            XcStream(
                streamId = streamId,
                name = o.str("name") ?: "",
                logo = o.str("stream_icon") ?: o.str("cover"),
                categoryId = o.str("category_id"),
                epgChannelId = o.str("epg_channel_id"),
                archive = o.bool("tv_archive"),
                archiveDurationMin = o.int("tv_archive_duration") ?: 0,
                containerExt = o.str("container_extension"),
                plot = o.str("plot"),
                rating = o.double("rating_5based")?.let { it * 2 } ?: o.double("rating"),
                seriesId = o.long("series_id"),
                releaseDate = o.str("releaseDate") ?: o.str("release_date"),
            )
        } ?: emptyList()
    }

    suspend fun getVodInfo(acc: XcAccount, vodId: Long): XcVodInfo? {
        val root = call(acc, "get_vod_info", "vod_id" to vodId.toString()) as? JsonObject
            ?: return null
        val info = root.obj("info") ?: root
        val movie = root.obj("movie_data")
        val ext = movie?.str("container_extension")
            ?: info.str("container_extension")
            ?: "mp4"
        return XcVodInfo(
            name = movie?.str("name") ?: info.str("name") ?: "",
            plot = info.str("plot") ?: info.str("description"),
            poster = info.str("movie_image") ?: info.str("cover_big"),
            containerExt = ext,
            rating = info.double("rating"),
            genre = info.str("genre"),
            year = info.str("releasedate") ?: info.str("year"),
            director = info.str("director"),
            cast = info.str("cast") ?: info.str("actors"),
            duration = info.str("duration"),
        )
    }

    suspend fun getSeriesInfo(acc: XcAccount, seriesId: Long): XcSeriesInfo? {
        val root = call(acc, "get_series_info", "series_id" to seriesId.toString())
            as? JsonObject ?: return null
        val info = root.obj("info") ?: root
        val episodesRoot = root.obj("episodes")

        val seasons = mutableMapOf<Int, List<XcEpisode>>()
        if (episodesRoot != null) {
            for ((seasonKey, value) in episodesRoot) {
                val season = seasonKey.toIntOrNull() ?: continue
                val episodeList = (value as? JsonArray)
                    ?.filterIsInstance<JsonObject>() ?: continue
                seasons[season] = episodeList.mapNotNull { e ->
                    val id = e.long("id") ?: return@mapNotNull null
                    XcEpisode(
                        id = id,
                        title = e.str("title") ?: "",
                        episodeNum = e.int("episode_num") ?: 0,
                        season = e.int("season") ?: season,
                        containerExt = e.str("container_extension") ?: "mp4",
                        plot = e.obj("info")?.str("plot"),
                        poster = e.obj("info")?.str("movie_image"),
                        duration = e.obj("info")?.str("duration"),
                    )
                }
            }
        }
        return XcSeriesInfo(
            name = info.str("name") ?: info.str("originaltitle") ?: "",
            plot = info.str("plot"),
            poster = info.str("cover"),
            genre = info.str("genre"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            rating = info.double("rating"),
            cast = info.str("cast"),
            director = info.str("director"),
            seasons = seasons.toSortedMap(),
        )
    }

    /** Short EPG for one live stream (get_simple_data_table). */
    suspend fun getStreamEpg(acc: XcAccount, streamId: Long): List<XcEpgItem> {
        val root = call(acc, "get_simple_data_table", "stream_id" to streamId.toString())
            as? JsonObject ?: return emptyList()
        return root.array("epg_listings").mapNotNull { listing ->
            val start = listing.long("start_timestamp") ?: return@mapNotNull null
            val stop = listing.long("stop_timestamp")
                ?: (start + (listing.long("duration_secs") ?: 0L))
            XcEpgItem(
                title = listing.str("title")?.let { Base64Codec.decodeToString(it) } ?: "",
                description = listing.str("description")?.let { Base64Codec.decodeToString(it) },
                start = start,
                stop = stop,
            )
        }
    }
}
