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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.protocol.rtorrent

import java.net.URLDecoder
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.transdroid.protocol.AddOptions
import org.transdroid.protocol.DaemonAdapter
import org.transdroid.protocol.DaemonCapability
import org.transdroid.protocol.DaemonConfig
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.SessionStats
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.internal.executeOnIo

/**
 * Adapter for rTorrent 0.9.7+ via XML-RPC over HTTP, the common seedbox setup where a web
 * server (or ruTorrent) exposes the SCGI socket at an endpoint like /RPC2. Uses the modern
 * dotted command names (d.multicall2, load.start); the pre-0.9.7 underscore API is not
 * supported.
 */
class RtorrentAdapter(
    override val config: DaemonConfig,
    private val httpClient: OkHttpClient,
) : DaemonAdapter {

    private val rpcUrl = config.baseUrl +
        (config.path?.takeIf { it.isNotBlank() } ?: "/RPC2").let { if (it.startsWith("/")) it else "/$it" }

    override val capabilities: Set<DaemonCapability> = CAPABILITIES

    override suspend fun testConnection(): String {
        val version = call("system.client_version") as? String ?: "unknown"
        return "rTorrent $version"
    }

    override suspend fun listTorrents(): List<Torrent> {
        val rows = call(
            "d.multicall2",
            "", "main",
            "d.hash=", "d.name=", "d.state=", "d.complete=", "d.is_active=", "d.hashing=",
            "d.down.rate=", "d.up.rate=", "d.size_bytes=", "d.completed_bytes=", "d.up.total=",
            "d.ratio=", "d.peers_connected=", "d.timestamp.started=", "d.directory=", "d.message=",
            "d.custom1=",
        ) as? List<*> ?: throw DaemonException.UnexpectedResponse("Unexpected d.multicall2 reply")
        return rows.map { row ->
            val fields = row as? List<*> ?: throw DaemonException.UnexpectedResponse("Bad multicall row")
            parseTorrent(fields)
        }
    }

    private fun parseTorrent(fields: List<*>): Torrent {
        fun str(index: Int) = fields.getOrNull(index)?.toString().orEmpty()
        fun num(index: Int): Long = when (val value = fields.getOrNull(index)) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }

        val state = num(2)
        val complete = num(3) == 1L
        val isActive = num(4) == 1L
        val hashing = num(5) > 0L
        val downRate = num(6)
        val sizeBytes = num(8)
        val completedBytes = num(9)
        // d.message carries routine tracker notices ("Tried all trackers") while the
        // torrent keeps working via other trackers/DHT — informational, never a status
        val message = str(15).takeIf { it.isNotBlank() }

        val status = when {
            hashing -> TorrentStatus.CHECKING
            state == 0L || !isActive -> TorrentStatus.PAUSED
            complete -> TorrentStatus.SEEDING
            else -> TorrentStatus.DOWNLOADING
        }
        val eta = if (status == TorrentStatus.DOWNLOADING && downRate > 0) {
            (sizeBytes - completedBytes) / downRate
        } else {
            null
        }
        return Torrent(
            id = str(0),
            name = str(1),
            status = status,
            progress = if (sizeBytes <= 0) 0f else (completedBytes.toFloat() / sizeBytes).coerceIn(0f, 1f),
            downloadRate = downRate,
            uploadRate = num(7),
            etaSeconds = eta,
            sizeBytes = sizeBytes,
            downloadedBytes = completedBytes,
            uploadedBytes = num(10),
            // d.ratio is reported in per-mille
            ratio = num(11) / 1000f,
            peersConnected = num(12).toInt(),
            addedTimestamp = num(13).takeIf { it > 0 },
            downloadDir = str(14).takeIf { it.isNotBlank() },
            error = message,
            // ruTorrent stores its label encodeURIComponent-encoded in custom1; that
            // encoding leaves "+" literal, so protect it from URLDecoder's plus-to-space
            labels = str(16).takeIf { it.isNotBlank() }?.let { raw ->
                listOf(
                    try {
                        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        raw
                    }
                )
            } ?: emptyList(),
        )
    }

    override suspend fun addByUrl(url: String, options: AddOptions) {
        // load.normal loads without starting; load.start loads and starts
        call(if (options.startPaused) "load.normal" else "load.start", "", url)
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray, options: AddOptions) {
        // rTorrent's default XML-RPC request size limit (~512 KiB) rejects larger
        // base64-encoded uploads; raise it first like ruTorrent does. Best-effort:
        // some locked-down hosts refuse the command, and small files work regardless.
        try {
            val limit = maxOf(2L * 1024 * 1024, contents.size * 2L + 1280L)
            call("network.xmlrpc.size_limit.set", "", limit)
        } catch (e: DaemonException.UnexpectedResponse) {
            // Proceed; the load below fails with a clear fault if the file is too big
        }
        call(if (options.startPaused) "load.raw" else "load.raw_start", "", contents)
    }

    override suspend fun start(torrentId: String) {
        call("d.start", torrentId)
    }

    override suspend fun pause(torrentId: String) {
        call("d.stop", torrentId)
    }

    override suspend fun remove(torrentId: String, deleteData: Boolean) {
        // rTorrent has no built-in "delete data". The UI hides that checkbox via capabilities.
        call("d.erase", torrentId)
    }

    override suspend fun setLabels(torrentId: String, labels: List<String>) {
        val encoded = java.net.URLEncoder.encode(labels.firstOrNull().orEmpty(), "UTF-8")
        call("d.custom1.set", torrentId, encoded)
    }

    override suspend fun setLocation(torrentId: String, path: String, moveData: Boolean) {
        call("d.directory.set", torrentId, path)
    }

    override suspend fun recheck(torrentId: String) {
        call("d.check_hash", torrentId)
    }

    override suspend fun reannounce(torrentId: String) {
        call("d.tracker_announce", torrentId)
    }

    override suspend fun setGlobalSpeedLimits(downloadBytesPerSec: Long?, uploadBytesPerSec: Long?) {
        downloadBytesPerSec?.let { call("throttle.global_down.max_rate.set", "", it) }
        uploadBytesPerSec?.let { call("throttle.global_up.max_rate.set", "", it) }
    }

    override suspend fun sessionStats(): SessionStats {
        val down = (call("throttle.global_down.rate") as? Number)?.toLong() ?: 0L
        val up = (call("throttle.global_up.rate") as? Number)?.toLong() ?: 0L
        val downLimit = (call("throttle.global_down.max_rate") as? Number)?.toLong()
        val upLimit = (call("throttle.global_up.max_rate") as? Number)?.toLong()
        val downloadDir = (call("directory.default") as? String)?.takeIf { it.isNotBlank() }
        return SessionStats(
            downloadRate = down,
            uploadRate = up,
            downloadLimitBytesPerSec = downLimit,
            uploadLimitBytesPerSec = upLimit,
            downloadDir = downloadDir,
        )
    }

    override suspend fun listFiles(torrentId: String): List<TorrentFile> {
        val rows = call(
            "f.multicall",
            torrentId, "",
            "f.path=", "f.size_bytes=", "f.completed_chunks=", "f.size_chunks=", "f.priority=",
        ) as? List<*> ?: throw DaemonException.UnexpectedResponse("Unexpected f.multicall reply")
        return rows.mapIndexed { index, row ->
            val fields = row as? List<*> ?: throw DaemonException.UnexpectedResponse("Bad multicall row")
            fun num(index: Int): Long = when (val value = fields.getOrNull(index)) {
                is Long -> value
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
            val size = num(1)
            val completedChunks = num(2)
            val sizeChunks = num(3)
            TorrentFile(
                index = index,
                path = fields.getOrNull(0)?.toString().orEmpty(),
                sizeBytes = size,
                downloadedBytes = if (sizeChunks <= 0) 0L else size * completedChunks / sizeChunks,
                priority = when (num(4)) {
                    0L -> FilePriority.OFF
                    2L -> FilePriority.HIGH
                    else -> FilePriority.NORMAL
                },
            )
        }
    }

    override suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority) {
        // rTorrent priorities: 0 = off, 1 = normal (LOW folds into it), 2 = high
        val value = when (priority) {
            FilePriority.OFF -> 0L
            FilePriority.HIGH -> 2L
            else -> 1L
        }
        call("f.priority.set", "$torrentId:f$fileIndex", value)
        call("d.update_priorities", torrentId)
    }

    private suspend fun call(method: String, vararg params: Any?): Any? {
        val builder = Request.Builder()
            .url(rpcUrl)
            .post(XmlRpc.buildRequest(method, params.toList()).toRequestBody("text/xml".toMediaType()))
        val username = config.username
        if (!username.isNullOrEmpty()) {
            builder.header("Authorization", Credentials.basic(username, config.password.orEmpty()))
        }
        httpClient.executeOnIo(builder.build()).use { response ->
            when {
                response.code == 401 || response.code == 403 ->
                    throw DaemonException.Authentication("rTorrent's web server rejected the username/password")
                !response.isSuccessful ->
                    throw DaemonException.UnexpectedResponse("rTorrent returned HTTP ${response.code}")
            }
            return XmlRpc.parseResponse(response.body?.string().orEmpty())
        }
    }

    private companion object {
        val CAPABILITIES = setOf(
            DaemonCapability.SET_LABELS,
            DaemonCapability.SET_LOCATION,
            DaemonCapability.RECHECK,
            DaemonCapability.REANNOUNCE,
            DaemonCapability.GLOBAL_SPEED_LIMITS,
            DaemonCapability.SESSION_STATS,
        )
    }
}
