package com.iptvplayer.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared OkHttp client and simple download helpers. */
object Http {

    const val USER_AGENT = "IP-TV-Player/${com.iptvplayer.app.BuildConfig.VERSION_NAME} (Android; okhttp)"

    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /** GET a text body (M3U / API / manifest). Throws [IOException] on failure. */
    suspend fun getString(client: OkHttpClient, url: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
                response.body?.string() ?: ""
            }
        }

    /**
     * GET binary content into [target] (EPG files, update APKs).
     * [onProgress] receives (bytesRead, totalBytesOrNull) on the IO dispatcher.
     */
    suspend fun downloadToFile(
        client: OkHttpClient,
        url: String,
        target: File,
        onProgress: ((Long, Long?) -> Unit)? = null,
    ): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            target.parentFile?.mkdirs()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
                val body = response.body ?: throw IOException("Empty body: $url")
                val total = body.contentLength().takeIf { it > 0 }
                var read = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress?.invoke(read, total)
                        }
                    }
                }
                target
            }
        }
}
