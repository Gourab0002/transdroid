/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.protocol

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.transdroid.protocol.deluge.DelugeAdapter
import org.transdroid.protocol.qbittorrent.QbittorrentAdapter
import org.transdroid.protocol.rtorrent.RtorrentAdapter
import org.transdroid.protocol.transmission.TransmissionAdapter

/**
 * A connection to one torrent daemon. Implementations keep only connection/session bookkeeping
 * and are safe to call concurrently; I/O is performed on OkHttp's dispatcher and cancelled with
 * the calling coroutine. All methods throw [DaemonException] on failure.
 *
 * Optional operations ([recheck], [setLabels], …) default to [DaemonException.Unsupported].
 * Callers should consult [capabilities] and hide those actions in the UI.
 */
interface DaemonAdapter {
    val config: DaemonConfig

    val capabilities: Set<DaemonCapability>
        get() = emptySet()

    /** Verifies connectivity and credentials, returning a daemon version description. */
    suspend fun testConnection(): String

    suspend fun listTorrents(): List<Torrent>

    /**
     * Adds a torrent by magnet link or a URL to a .torrent file. With [startPaused] the
     * torrent is added stopped, so files can be deselected before starting it.
     */
    suspend fun addByUrl(url: String, startPaused: Boolean = false) =
        addByUrl(url, AddOptions(startPaused = startPaused))

    suspend fun addByUrl(url: String, options: AddOptions)

    /** Adds a torrent from the raw bytes of a .torrent file. */
    suspend fun addByFile(fileName: String, contents: ByteArray, startPaused: Boolean = false) =
        addByFile(fileName, contents, AddOptions(startPaused = startPaused))

    suspend fun addByFile(fileName: String, contents: ByteArray, options: AddOptions)

    suspend fun start(torrentId: String)

    suspend fun pause(torrentId: String)

    suspend fun start(torrentIds: List<String>) {
        torrentIds.forEach { start(it) }
    }

    suspend fun pause(torrentIds: List<String>) {
        torrentIds.forEach { pause(it) }
    }

    suspend fun remove(torrentId: String, deleteData: Boolean)

    suspend fun remove(torrentIds: List<String>, deleteData: Boolean) {
        torrentIds.forEach { remove(it, deleteData) }
    }

    suspend fun listFiles(torrentId: String): List<TorrentFile>

    /**
     * Changes one file's download priority. Clients without a LOW level treat LOW as
     * NORMAL; OFF always means "do not download".
     */
    suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority)

    suspend fun setLabels(torrentId: String, labels: List<String>) {
        unsupported("set labels")
    }

    suspend fun setLocation(torrentId: String, path: String, moveData: Boolean = true) {
        unsupported("set location")
    }

    suspend fun recheck(torrentId: String) {
        unsupported("recheck")
    }

    suspend fun reannounce(torrentId: String) {
        unsupported("reannounce")
    }

    /**
     * Per-torrent speed caps in bytes/second. Null leaves that direction unchanged;
     * 0 means unlimited.
     */
    suspend fun setTorrentSpeedLimits(
        torrentId: String,
        downloadBytesPerSec: Long?,
        uploadBytesPerSec: Long?,
    ) {
        unsupported("torrent speed limits")
    }

    /**
     * Global speed caps in bytes/second. Null leaves that direction unchanged;
     * 0 means unlimited.
     */
    suspend fun setGlobalSpeedLimits(downloadBytesPerSec: Long?, uploadBytesPerSec: Long?) {
        unsupported("global speed limits")
    }

    suspend fun setAltSpeedEnabled(enabled: Boolean) {
        unsupported("alternative speed limits")
    }

    suspend fun sessionStats(): SessionStats {
        unsupported("session stats")
    }

    suspend fun listTrackers(torrentId: String): List<TorrentTracker> {
        unsupported("list trackers")
    }

    suspend fun listPeers(torrentId: String): List<TorrentPeer> {
        unsupported("list peers")
    }

    suspend fun forceStart(torrentId: String) {
        unsupported("force start")
    }

    suspend fun moveQueue(torrentId: String, move: QueueMove) {
        unsupported("queue")
    }

    private fun unsupported(action: String): Nothing =
        throw DaemonException.Unsupported("This client cannot $action")
}

object DaemonAdapterFactory {

    const val USER_AGENT = "Transdroid/3"

    private val RESERVED_HEADER_NAMES = setOf(
        "cookie", "authorization", "host", "content-length", "content-type", "connection",
    )

    /** A default client with timeouts suited for home servers and seedboxes. */
    fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(userAgentInterceptor())
        .build()

    fun create(config: DaemonConfig, httpClient: OkHttpClient = defaultHttpClient()): DaemonAdapter {
        var client = config.pinnedCertSha256
            ?.takeIf { it.isNotBlank() }
            ?.let { Tls.clientWithPinnedCertificate(httpClient, it) }
            ?: httpClient
        val headers = config.customHeaders.filterKeys { it.lowercase() !in RESERVED_HEADER_NAMES }
        if (headers.isNotEmpty()) {
            client = client.newBuilder().addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
                chain.proceed(request)
            }.build()
        }
        return when (config.type) {
            DaemonType.TRANSMISSION -> TransmissionAdapter(config, client)
            DaemonType.QBITTORRENT -> QbittorrentAdapter(config, client)
            DaemonType.RTORRENT -> RtorrentAdapter(config, client)
            DaemonType.DELUGE -> DelugeAdapter(config, client)
        }
    }

    private fun userAgentInterceptor(): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val request = if (original.header("User-Agent") == null) {
            original.newBuilder().header("User-Agent", USER_AGENT).build()
        } else {
            original
        }
        chain.proceed(request)
    }
}
