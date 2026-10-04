package com.iptvplayer.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.UdpDataSource

/**
 * DataSource factory for IPTV streams:
 *  - http/https via OkHttp (shared client, custom User-Agent + headers);
 *  - udp/rtp via [UdpDataSource];
 *  - everything else (file, asset, content, rtmp through media3's reflective
 *    support, …) via [DefaultDataSource].
 */
@OptIn(UnstableApi::class)
class IptvDataSourceFactory(
    private val context: Context,
    private val httpFactory: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        IptvDataSource(context.applicationContext, httpFactory.createDataSource())
}

@OptIn(UnstableApi::class)
private class IptvDataSource(
    private val context: Context,
    private val http: DataSource,
) : DataSource {

    private var delegate: DataSource? = null

    private fun pick(dataSpec: DataSpec): DataSource {
        val scheme = dataSpec.uri.scheme?.lowercase()
        return when (scheme) {
            "http", "https" -> http
            "udp", "rtp" -> UdpDataSource()
            else -> DefaultDataSource(context, http)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = pick(dataSpec)
        delegate = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun addTransferListener(transferListener: TransferListener) {
        // No-op: transfer listeners are not required for this app.
    }

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}
