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
package org.transdroid.protocol.transmission

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
import org.transdroid.protocol.QueueMove
import org.transdroid.protocol.SessionStats
import org.transdroid.protocol.internal.executeOnIo
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentPeer
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.TorrentTracker

/**
 * Adapter for the Transmission RPC protocol (JSON over HTTP POST), as documented in
 * https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md. Handles the
 * CSRF handshake where the daemon answers 409 with a fresh X-Transmission-Session-Id.
 */
class TransmissionAdapter(
    override val config: DaemonConfig,
    private val httpClient: OkHttpClient,
) : DaemonAdapter {

    private val json = Json { ignoreUnknownKeys = true }
    private val rpcUrl = config.baseUrl +
        (config.path?.takeIf { it.isNotBlank() } ?: "/transmission/rpc")
            .let { if (it.startsWith("/")) it else "/$it" }

    @Volatile
    private var sessionId: String? = null

    override val capabilities: Set<DaemonCapability> = CAPABILITIES

    override suspend fun testConnection(): String {
        val arguments = request("session-get")
        val version = arguments["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val rpcVersion = arguments["rpc-version"]?.jsonPrimitive?.contentOrNull
        return "Transmission $version" + (rpcVersion?.let { " (RPC v$it)" } ?: "")
    }

    override suspend fun listTorrents(): List<Torrent> {
        val arguments = request("torrent-get") {
            put("fields", buildJsonArray { TORRENT_FIELDS.forEach { add(it) } })
        }
        val torrents = arguments["torrents"]?.jsonArray
            ?: throw DaemonException.UnexpectedResponse("Missing 'torrents' in torrent-get response")
        return torrents.map { parseTorrent(it.jsonObject) }
    }

    override suspend fun addByUrl(url: String, options: AddOptions) {
        request("torrent-add") {
            put("filename", url)
            putAddOptions(options)
        }
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray, options: AddOptions) {
        request("torrent-add") {
            put("metainfo", Base64.getEncoder().encodeToString(contents))
            putAddOptions(options)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAddOptions(options: AddOptions) {
        if (options.startPaused) put("paused", true)
        options.downloadDir?.takeIf { it.isNotBlank() }?.let { put("download-dir", it) }
        if (options.labels.isNotEmpty()) {
            put("labels", buildJsonArray { options.labels.forEach { add(it) } })
        }
    }

    override suspend fun start(torrentId: String) {
        request("torrent-start") { putIds(torrentId) }
    }

    override suspend fun pause(torrentId: String) {
        request("torrent-stop") { putIds(torrentId) }
    }

    override suspend fun start(torrentIds: List<String>) {
        if (torrentIds.isEmpty()) return
        request("torrent-start") { putIds(torrentIds) }
    }

    override suspend fun pause(torrentIds: List<String>) {
        if (torrentIds.isEmpty()) return
        request("torrent-stop") { putIds(torrentIds) }
    }

    override suspend fun remove(torrentId: String, deleteData: Boolean) {
        request("torrent-remove") {
            putIds(torrentId)
            put("delete-local-data", deleteData)
        }
    }

    override suspend fun remove(torrentIds: List<String>, deleteData: Boolean) {
        if (torrentIds.isEmpty()) return
        request("torrent-remove") {
            putIds(torrentIds)
            put("delete-local-data", deleteData)
        }
    }

    override suspend fun setLabels(torrentId: String, labels: List<String>) {
        request("torrent-set") {
            putIds(torrentId)
            put("labels", buildJsonArray { labels.forEach { add(it) } })
        }
    }

    override suspend fun setLocation(torrentId: String, path: String, moveData: Boolean) {
        request("torrent-set-location") {
            putIds(torrentId)
            put("location", path)
            put("move", moveData)
        }
    }

    override suspend fun recheck(torrentId: String) {
        request("torrent-verify") { putIds(torrentId) }
    }

    override suspend fun reannounce(torrentId: String) {
        request("torrent-reannounce") { putIds(torrentId) }
    }

    override suspend fun setTorrentSpeedLimits(
        torrentId: String,
        downloadBytesPerSec: Long?,
        uploadBytesPerSec: Long?,
    ) {
        request("torrent-set") {
            putIds(torrentId)
            downloadBytesPerSec?.let { bytes ->
                if (bytes <= 0) {
                    put("downloadLimited", false)
                } else {
                    put("downloadLimited", true)
                    put("downloadLimit", (bytes / 1000).coerceAtLeast(1))
                }
            }
            uploadBytesPerSec?.let { bytes ->
                if (bytes <= 0) {
                    put("uploadLimited", false)
                } else {
                    put("uploadLimited", true)
                    put("uploadLimit", (bytes / 1000).coerceAtLeast(1))
                }
            }
        }
    }

    override suspend fun setGlobalSpeedLimits(downloadBytesPerSec: Long?, uploadBytesPerSec: Long?) {
        request("session-set") {
            downloadBytesPerSec?.let { bytes ->
                if (bytes <= 0) {
                    put("speed-limit-down-enabled", false)
                } else {
                    put("speed-limit-down-enabled", true)
                    put("speed-limit-down", (bytes / 1000).coerceAtLeast(1))
                }
            }
            uploadBytesPerSec?.let { bytes ->
                if (bytes <= 0) {
                    put("speed-limit-up-enabled", false)
                } else {
                    put("speed-limit-up-enabled", true)
                    put("speed-limit-up", (bytes / 1000).coerceAtLeast(1))
                }
            }
        }
    }

    override suspend fun setAltSpeedEnabled(enabled: Boolean) {
        request("session-set") { put("alt-speed-enabled", enabled) }
    }

    override suspend fun listTrackers(torrentId: String): List<TorrentTracker> {
        val arguments = request("torrent-get") {
            putIds(torrentId)
            put("fields", buildJsonArray { add("trackerStats") })
        }
        val torrent = arguments["torrents"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return emptyList()
        return torrent["trackerStats"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            TorrentTracker(
                url = obj["announce"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                working = obj["lastAnnounceSucceeded"]?.jsonPrimitive?.contentOrNull == "true",
                seeders = obj["seederCount"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 },
                leechers = obj["leecherCount"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 },
                message = obj["lastAnnounceResult"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "Success" },
            )
        }?.filter { it.url.isNotBlank() } ?: emptyList()
    }

    override suspend fun listPeers(torrentId: String): List<TorrentPeer> {
        val arguments = request("torrent-get") {
            putIds(torrentId)
            put("fields", buildJsonArray { add("peers") })
        }
        val torrent = arguments["torrents"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return emptyList()
        return torrent["peers"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            TorrentPeer(
                address = obj["address"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                client = obj["clientName"]?.jsonPrimitive?.contentOrNull,
                progress = obj["progress"]?.jsonPrimitive?.floatOrNull ?: 0f,
                downloadRate = obj["rateToClient"]?.jsonPrimitive?.long ?: 0L,
                uploadRate = obj["rateToPeer"]?.jsonPrimitive?.long ?: 0L,
                isSeed = (obj["progress"]?.jsonPrimitive?.floatOrNull ?: 0f) >= 1f,
            )
        }?.filter { it.address.isNotBlank() } ?: emptyList()
    }

    override suspend fun forceStart(torrentId: String) {
        request("torrent-start-now") { putIds(torrentId) }
    }

    override suspend fun moveQueue(torrentId: String, move: QueueMove) {
        val method = when (move) {
            QueueMove.TOP -> "queue-move-top"
            QueueMove.UP -> "queue-move-up"
            QueueMove.DOWN -> "queue-move-down"
            QueueMove.BOTTOM -> "queue-move-bottom"
        }
        request(method) { putIds(torrentId) }
    }

    override suspend fun sessionStats(): SessionStats {
        val session = request("session-get")
        val stats = request("session-stats")
        val downloadDir = session["download-dir"]?.jsonPrimitive?.contentOrNull
        val free = try {
            downloadDir?.let { dir ->
                request("free-space") { put("path", dir) }["size-bytes"]?.jsonPrimitive?.long
            }
        } catch (e: DaemonException) {
            null
        }
        fun kbps(enabledKey: String, valueKey: String): Long? {
            val enabled = session[enabledKey]?.jsonPrimitive?.contentOrNull == "true"
            if (!enabled) return 0L
            val kb = session[valueKey]?.jsonPrimitive?.long ?: return 0L
            return kb * 1000
        }
        return SessionStats(
            downloadRate = stats["downloadSpeed"]?.jsonPrimitive?.long ?: 0L,
            uploadRate = stats["uploadSpeed"]?.jsonPrimitive?.long ?: 0L,
            downloadLimitBytesPerSec = kbps("speed-limit-down-enabled", "speed-limit-down"),
            uploadLimitBytesPerSec = kbps("speed-limit-up-enabled", "speed-limit-up"),
            altSpeedEnabled = session["alt-speed-enabled"]?.jsonPrimitive?.contentOrNull == "true",
            freeSpaceBytes = free,
            downloadDir = downloadDir,
        )
    }

    override suspend fun listFiles(torrentId: String): List<TorrentFile> {
        val arguments = request("torrent-get") {
            putIds(torrentId)
            put("fields", buildJsonArray { listOf("files", "fileStats").forEach { add(it) } })
        }
        val torrent = arguments["torrents"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw DaemonException.UnexpectedResponse("Torrent $torrentId not found")
        val files = torrent["files"]?.jsonArray ?: return emptyList()
        val stats = torrent["fileStats"]?.jsonArray
        return files.mapIndexed { index, element ->
            val file = element.jsonObject
            val stat = stats?.getOrNull(index)?.jsonObject
            val wanted = stat?.get("wanted")?.jsonPrimitive?.contentOrNull != "false"
            val priority = when {
                !wanted -> FilePriority.OFF
                else -> when (stat?.get("priority")?.jsonPrimitive?.intOrNull) {
                    -1 -> FilePriority.LOW
                    1 -> FilePriority.HIGH
                    else -> FilePriority.NORMAL
                }
            }
            TorrentFile(
                index = index,
                path = file["name"]?.jsonPrimitive?.contentOrNull ?: "",
                sizeBytes = file["length"]?.jsonPrimitive?.long ?: 0L,
                downloadedBytes = file["bytesCompleted"]?.jsonPrimitive?.long ?: 0L,
                priority = priority,
            )
        }
    }

    override suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority) {
        request("torrent-set") {
            putIds(torrentId)
            val indexes = buildJsonArray { add(fileIndex) }
            if (priority == FilePriority.OFF) {
                put("files-unwanted", indexes)
            } else {
                put("files-wanted", indexes)
                val key = when (priority) {
                    FilePriority.LOW -> "priority-low"
                    FilePriority.HIGH -> "priority-high"
                    else -> "priority-normal"
                }
                put(key, indexes)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIds(torrentId: String) {
        putIds(listOf(torrentId))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIds(torrentIds: List<String>) {
        put("ids", buildJsonArray {
            torrentIds.forEach { torrentId ->
                val id = torrentId.toIntOrNull()
                    ?: throw DaemonException.UnexpectedResponse("Not a Transmission torrent id: $torrentId")
                add(id)
            }
        })
    }

    /** Sends one RPC request, retrying once after a 409 session-id challenge. */
    private suspend fun request(
        method: String,
        argumentsBuilder: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ): JsonObject {
        val body = buildJsonObject {
            put("method", method)
            if (argumentsBuilder != null) putJsonObject("arguments", argumentsBuilder)
        }.toString()

        var response = send(body)
        if (response.code == 409) {
            sessionId = response.header(SESSION_ID_HEADER)
            response.close()
            response = send(body)
        }
        response.use {
            when {
                it.code == 401 || it.code == 403 ->
                    throw DaemonException.Authentication("Transmission rejected the username/password")
                !it.isSuccessful ->
                    throw DaemonException.UnexpectedResponse("Transmission returned HTTP ${it.code}")
            }
            val text = it.body?.string().orEmpty()
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                // A reverse proxy or access portal (e.g. Cloudflare Access) commonly answers
                // with an HTML login page; make that diagnosable instead of a generic error
                val hint = if (text.trimStart().startsWith("<")) {
                    "Transmission's address answered with a web page instead of RPC — " +
                        "a login portal (such as Cloudflare Access) may be in front of it"
                } else {
                    "Not a Transmission RPC response"
                }
                throw DaemonException.UnexpectedResponse(hint, e)
            }
            val result = root["result"]?.jsonPrimitive?.contentOrNull
            if (result != "success") {
                throw DaemonException.UnexpectedResponse("Transmission error: ${result ?: "no result"}")
            }
            return root["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    private suspend fun send(body: String): okhttp3.Response {
        val builder = Request.Builder()
            .url(rpcUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
        sessionId?.let { builder.header(SESSION_ID_HEADER, it) }
        val username = config.username
        if (!username.isNullOrEmpty()) {
            builder.header("Authorization", Credentials.basic(username, config.password.orEmpty()))
        }
        return httpClient.executeOnIo(builder.build())
    }

    private fun parseTorrent(obj: JsonObject): Torrent {
        // Transmission fills errorString for routine tracker warnings too (error codes
        // 1/2) while the torrent keeps working; only code 3 is a real local error
        val errorCode = obj["error"]?.jsonPrimitive?.intOrNull ?: 0
        val errorText = obj["errorString"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && errorCode != 0 }
        val statusCode = obj["status"]?.jsonPrimitive?.int ?: -1
        val status = when {
            errorCode == 3 -> TorrentStatus.ERROR
            else -> when (statusCode) {
                0 -> TorrentStatus.PAUSED
                1, 2 -> TorrentStatus.CHECKING
                3, 5 -> TorrentStatus.QUEUED
                4 -> TorrentStatus.DOWNLOADING
                6 -> TorrentStatus.SEEDING
                else -> TorrentStatus.UNKNOWN
            }
        }
        val eta = obj["eta"]?.jsonPrimitive?.long?.takeIf { it >= 0 }
        return Torrent(
            id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: throw DaemonException.UnexpectedResponse("Torrent without id"),
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            status = status,
            progress = obj["percentDone"]?.jsonPrimitive?.float?.coerceIn(0f, 1f) ?: 0f,
            downloadRate = obj["rateDownload"]?.jsonPrimitive?.long ?: 0L,
            uploadRate = obj["rateUpload"]?.jsonPrimitive?.long ?: 0L,
            etaSeconds = eta,
            // sizeWhenDone/haveValid+haveUnchecked reflect the *wanted* files; totalSize
            // and downloadedEver would count deselected files and discarded data
            sizeBytes = obj["sizeWhenDone"]?.jsonPrimitive?.long ?: 0L,
            downloadedBytes = (obj["haveValid"]?.jsonPrimitive?.long ?: 0L) +
                (obj["haveUnchecked"]?.jsonPrimitive?.long ?: 0L),
            uploadedBytes = obj["uploadedEver"]?.jsonPrimitive?.long ?: 0L,
            ratio = (obj["uploadRatio"]?.jsonPrimitive?.float ?: 0f).coerceAtLeast(0f),
            peersConnected = obj["peersConnected"]?.jsonPrimitive?.int ?: 0,
            addedTimestamp = obj["addedDate"]?.jsonPrimitive?.long?.takeIf { it > 0 },
            downloadDir = obj["downloadDir"]?.jsonPrimitive?.contentOrNull,
            error = errorText,
            labels = obj["labels"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                ?: emptyList(),
            metadataProgress = obj["metadataPercentComplete"]?.jsonPrimitive?.floatOrNull
                ?.takeIf { it < 1f }
                ?.coerceAtLeast(0f),
        )
    }

    private companion object {
        const val SESSION_ID_HEADER = "X-Transmission-Session-Id"

        val CAPABILITIES = setOf(
            DaemonCapability.DELETE_DATA,
            DaemonCapability.SET_LABELS,
            DaemonCapability.SET_LOCATION,
            DaemonCapability.RECHECK,
            DaemonCapability.REANNOUNCE,
            DaemonCapability.TORRENT_SPEED_LIMITS,
            DaemonCapability.GLOBAL_SPEED_LIMITS,
            DaemonCapability.ALT_SPEED,
            DaemonCapability.SESSION_STATS,
            DaemonCapability.ADD_OPTIONS,
            DaemonCapability.TRACKERS,
            DaemonCapability.PEERS,
            DaemonCapability.FORCE_START,
            DaemonCapability.QUEUE,
        )

        val TORRENT_FIELDS = listOf(
            "id", "name", "status", "percentDone", "rateDownload", "rateUpload", "eta",
            "sizeWhenDone", "haveValid", "haveUnchecked", "uploadedEver", "uploadRatio",
            "peersConnected", "addedDate", "downloadDir", "error", "errorString", "labels",
            "metadataPercentComplete",
        )
    }
}
