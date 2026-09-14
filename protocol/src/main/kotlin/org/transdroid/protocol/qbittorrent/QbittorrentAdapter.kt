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
package org.transdroid.protocol.qbittorrent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
import org.transdroid.protocol.internal.joinPath

/**
 * Adapter for the qBittorrent Web API v2 (REST over HTTP with SID cookie auth), as documented
 * in https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-(qBittorrent-4.1). Also covers
 * qBittorrent 5.x, which renamed the pause/resume endpoints to stop/start; both are tried.
 */
class QbittorrentAdapter(
    override val config: DaemonConfig,
    private val httpClient: OkHttpClient,
) : DaemonAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var sessionCookie: String? = null

    override val capabilities: Set<DaemonCapability> = CAPABILITIES

    override suspend fun testConnection(): String {
        val version = get("api/v2/app/version").use { it.readBodyOrThrow() }
        return "qBittorrent $version"
    }

    override suspend fun listTorrents(): List<Torrent> {
        val body = get("api/v2/torrents/info").use { it.readBodyOrThrow() }
        val infos = try {
            json.decodeFromString<List<TorrentInfo>>(body)
        } catch (e: Exception) {
            throw DaemonException.UnexpectedResponse("Cannot parse qBittorrent torrent list", e)
        }
        return infos.map { it.toTorrent() }
    }

    override suspend fun addByUrl(url: String, options: AddOptions) {
        val form = FormBody.Builder().add("urls", url).apply { putAddOptions(options) }.build()
        post("api/v2/torrents/add", form).use { it.checkAddSucceeded() }
    }

    override suspend fun addByFile(fileName: String, contents: ByteArray, options: AddOptions) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "torrents", fileName,
                contents.toRequestBody("application/x-bittorrent".toMediaType()),
            )
            .apply {
                if (options.startPaused) {
                    addFormDataPart("paused", "true")
                    addFormDataPart("stopped", "true")
                }
                options.downloadDir?.takeIf { it.isNotBlank() }?.let { addFormDataPart("savepath", it) }
                options.labels.firstOrNull()?.takeIf { it.isNotBlank() }?.let { addFormDataPart("category", it) }
            }
            .build()
        post("api/v2/torrents/add", body).use { it.checkAddSucceeded() }
    }

    private fun FormBody.Builder.putAddOptions(options: AddOptions) {
        if (options.startPaused) {
            // qBittorrent 4.x reads "paused", 5.x reads "stopped"; unknown fields are ignored
            add("paused", "true")
            add("stopped", "true")
        }
        options.downloadDir?.takeIf { it.isNotBlank() }?.let { add("savepath", it) }
        options.labels.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add("category", it) }
    }

    /** torrents/add reports failure as HTTP 200 with the body "Fails." */
    private fun Response.checkAddSucceeded() {
        if (readBodyOrThrow().trim() == "Fails.") {
            throw DaemonException.UnexpectedResponse("qBittorrent could not add that torrent")
        }
    }

    override suspend fun start(torrentId: String) {
        hashesActionWithFallback("start", "resume", torrentId)
    }

    override suspend fun pause(torrentId: String) {
        hashesActionWithFallback("stop", "pause", torrentId)
    }

    override suspend fun start(torrentIds: List<String>) {
        if (torrentIds.isEmpty()) return
        hashesActionWithFallback("start", "resume", torrentIds.joinToString("|"))
    }

    override suspend fun pause(torrentIds: List<String>) {
        if (torrentIds.isEmpty()) return
        hashesActionWithFallback("stop", "pause", torrentIds.joinToString("|"))
    }

    override suspend fun remove(torrentId: String, deleteData: Boolean) {
        val form = FormBody.Builder()
            .add("hashes", torrentId)
            .add("deleteFiles", deleteData.toString())
            .build()
        post("api/v2/torrents/delete", form).use { it.readBodyOrThrow() }
    }

    override suspend fun remove(torrentIds: List<String>, deleteData: Boolean) {
        if (torrentIds.isEmpty()) return
        val form = FormBody.Builder()
            .add("hashes", torrentIds.joinToString("|"))
            .add("deleteFiles", deleteData.toString())
            .build()
        post("api/v2/torrents/delete", form).use { it.readBodyOrThrow() }
    }

    override suspend fun setLabels(torrentId: String, labels: List<String>) {
        val form = FormBody.Builder()
            .add("hashes", torrentId)
            .add("category", labels.firstOrNull().orEmpty())
            .build()
        post("api/v2/torrents/setCategory", form).use { it.readBodyOrThrow() }
    }

    override suspend fun setLocation(torrentId: String, path: String, moveData: Boolean) {
        val form = FormBody.Builder()
            .add("hashes", torrentId)
            .add("location", path)
            .build()
        post("api/v2/torrents/setLocation", form).use { it.readBodyOrThrow() }
    }

    override suspend fun recheck(torrentId: String) {
        post("api/v2/torrents/recheck", FormBody.Builder().add("hashes", torrentId).build())
            .use { it.readBodyOrThrow() }
    }

    override suspend fun reannounce(torrentId: String) {
        post("api/v2/torrents/reannounce", FormBody.Builder().add("hashes", torrentId).build())
            .use { it.readBodyOrThrow() }
    }

    override suspend fun setTorrentSpeedLimits(
        torrentId: String,
        downloadBytesPerSec: Long?,
        uploadBytesPerSec: Long?,
    ) {
        downloadBytesPerSec?.let { bytes ->
            post(
                "api/v2/torrents/setDownloadLimit",
                FormBody.Builder().add("hashes", torrentId).add("limit", bytes.toString()).build(),
            ).use { it.readBodyOrThrow() }
        }
        uploadBytesPerSec?.let { bytes ->
            post(
                "api/v2/torrents/setUploadLimit",
                FormBody.Builder().add("hashes", torrentId).add("limit", bytes.toString()).build(),
            ).use { it.readBodyOrThrow() }
        }
    }

    override suspend fun setGlobalSpeedLimits(downloadBytesPerSec: Long?, uploadBytesPerSec: Long?) {
        downloadBytesPerSec?.let { bytes ->
            post("api/v2/transfer/setDownloadLimit", FormBody.Builder().add("limit", bytes.toString()).build())
                .use { it.readBodyOrThrow() }
        }
        uploadBytesPerSec?.let { bytes ->
            post("api/v2/transfer/setUploadLimit", FormBody.Builder().add("limit", bytes.toString()).build())
                .use { it.readBodyOrThrow() }
        }
    }

    override suspend fun setAltSpeedEnabled(enabled: Boolean) {
        val current = get("api/v2/transfer/speedLimitsMode").use { it.readBodyOrThrow().trim() == "1" }
        if (current != enabled) {
            post("api/v2/transfer/toggleSpeedLimitsMode", FormBody.Builder().build())
                .use { it.readBodyOrThrow() }
        }
    }

    override suspend fun sessionStats(): SessionStats {
        val transfer = get("api/v2/transfer/info").use { it.readBodyOrThrow() }
        val obj = try {
            json.parseToJsonElement(transfer).jsonObject
        } catch (e: Exception) {
            throw DaemonException.UnexpectedResponse("Cannot parse qBittorrent transfer info", e)
        }
        val prefsBody = get("api/v2/app/preferences").use { it.readBodyOrThrow() }
        val prefs = try {
            json.parseToJsonElement(prefsBody).jsonObject
        } catch (e: Exception) {
            null
        }
        return SessionStats(
            downloadRate = obj["dl_info_speed"]?.jsonPrimitive?.longOrNull ?: 0L,
            uploadRate = obj["up_info_speed"]?.jsonPrimitive?.longOrNull ?: 0L,
            downloadLimitBytesPerSec = obj["dl_rate_limit"]?.jsonPrimitive?.longOrNull,
            uploadLimitBytesPerSec = obj["up_rate_limit"]?.jsonPrimitive?.longOrNull,
            altSpeedEnabled = get("api/v2/transfer/speedLimitsMode").use { it.readBodyOrThrow().trim() == "1" },
            freeSpaceBytes = prefs?.get("free_space_on_disk")?.jsonPrimitive?.longOrNull,
            downloadDir = prefs?.get("save_path")?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun listFiles(torrentId: String): List<TorrentFile> {
        val body = get("api/v2/torrents/files", mapOf("hash" to torrentId)).use { it.readBodyOrThrow() }
        val files = try {
            json.decodeFromString<List<FileInfo>>(body)
        } catch (e: Exception) {
            throw DaemonException.UnexpectedResponse("Cannot parse qBittorrent file list", e)
        }
        return files.mapIndexed { listIndex, file ->
            TorrentFile(
                // Newer qBittorrent reports the real file index; fall back to list position
                index = file.index ?: listIndex,
                path = file.name,
                sizeBytes = file.size,
                downloadedBytes = if (file.size <= 0) 0L else (file.progress * file.size.toDouble()).toLong(),
                priority = when (file.priority) {
                    0 -> FilePriority.OFF
                    6, 7 -> FilePriority.HIGH
                    else -> FilePriority.NORMAL
                },
            )
        }
    }

    override suspend fun setFilePriority(torrentId: String, fileIndex: Int, priority: FilePriority) {
        // qBittorrent has no LOW level: 0 = skip, 1 = normal, 6 = high
        val value = when (priority) {
            FilePriority.OFF -> 0
            FilePriority.HIGH -> 6
            else -> 1
        }
        val form = FormBody.Builder()
            .add("hash", torrentId)
            .add("id", fileIndex.toString())
            .add("priority", value.toString())
            .build()
        post("api/v2/torrents/filePrio", form).use { it.readBodyOrThrow() }
    }

    /** qBittorrent 5 renamed pause/resume to stop/start; try new name first, fall back on 404. */
    private suspend fun hashesActionWithFallback(newEndpoint: String, legacyEndpoint: String, hash: String) {
        val form = { FormBody.Builder().add("hashes", hash).build() }
        val response = post("api/v2/torrents/$newEndpoint", form(), allowNotFound = true)
        if (response.code == 404) {
            response.close()
            post("api/v2/torrents/$legacyEndpoint", form()).use { it.readBodyOrThrow() }
        } else {
            response.use { it.readBodyOrThrow() }
        }
    }

    private suspend fun ensureAuthenticated() {
        if (sessionCookie != null || config.username.isNullOrEmpty()) return
        login()
    }

    private suspend fun login() {
        val form = FormBody.Builder()
            .add("username", config.username.orEmpty())
            .add("password", config.password.orEmpty())
            .build()
        val request = Request.Builder()
            .url(config.baseUrl + joinPath(config.path, "api/v2/auth/login"))
            .post(form)
            .build()
        httpClient.executeOnIo(request).use { response ->
            if (response.code == 403) {
                throw DaemonException.Authentication("qBittorrent blocked this address (too many failed logins)")
            }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.trim() != "Ok.") {
                throw DaemonException.Authentication("qBittorrent rejected the username/password")
            }
            // The session cookie is SID= historically; qBittorrent 5.1+ issues a
            // per-instance QBT_SID_<suffix>= cookie instead
            val cookie = response.headers("Set-Cookie").firstOrNull {
                val name = it.substringBefore('=')
                name == "SID" || name.startsWith("QBT_SID")
            } ?: throw DaemonException.UnexpectedResponse("qBittorrent login did not return a session cookie")
            sessionCookie = cookie.substringBefore(';')
        }
    }

    private suspend fun get(endpoint: String, query: Map<String, String> = emptyMap()): Response =
        sendAuthenticated {
            val url = (config.baseUrl + joinPath(config.path, endpoint)).toHttpUrl().newBuilder().apply {
                query.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            Request.Builder().url(url).get()
        }

    private suspend fun post(endpoint: String, body: okhttp3.RequestBody, allowNotFound: Boolean = false): Response =
        sendAuthenticated(allowNotFound) {
            Request.Builder().url(config.baseUrl + joinPath(config.path, endpoint)).post(body)
        }

    /** Sends a request with the SID cookie, re-authenticating once when the session expired. */
    private suspend fun sendAuthenticated(
        allowNotFound: Boolean = false,
        build: () -> Request.Builder,
    ): Response {
        ensureAuthenticated()
        var response = send(build())
        if (response.code == 403 && !config.username.isNullOrEmpty()) {
            response.close()
            login()
            response = send(build())
        }
        when {
            response.code == 401 || response.code == 403 -> {
                response.close()
                throw DaemonException.Authentication("qBittorrent requires a valid username/password")
            }
            response.code == 404 && allowNotFound -> return response
            !response.isSuccessful -> {
                val code = response.code
                response.close()
                throw DaemonException.UnexpectedResponse("qBittorrent returned HTTP $code")
            }
        }
        return response
    }

    private suspend fun send(builder: Request.Builder): Response {
        sessionCookie?.let { builder.header("Cookie", it) }
        return httpClient.executeOnIo(builder.build())
    }

    private fun Response.readBodyOrThrow(): String = body?.string().orEmpty()

    @Serializable
    private data class TorrentInfo(
        val hash: String,
        val name: String = "",
        val state: String = "",
        val progress: Float = 0f,
        val dlspeed: Long = 0,
        val upspeed: Long = 0,
        val eta: Long = INFINITE_ETA,
        val size: Long = 0,
        val completed: Long = 0,
        val uploaded: Long = 0,
        val ratio: Float = 0f,
        val num_seeds: Int = 0,
        val num_leechs: Int = 0,
        val added_on: Long = 0,
        val save_path: String? = null,
        val category: String = "",
        val tags: String = "",
    ) {
        fun toTorrent() = Torrent(
            id = hash,
            name = name,
            status = when (state) {
                "downloading", "metaDL", "forcedDL", "stalledDL", "forcedMetaDL" -> TorrentStatus.DOWNLOADING
                "uploading", "stalledUP", "forcedUP" -> TorrentStatus.SEEDING
                "pausedDL", "pausedUP", "stoppedDL", "stoppedUP" -> TorrentStatus.PAUSED
                "checkingDL", "checkingUP", "checkingResumeData", "allocating", "moving" -> TorrentStatus.CHECKING
                "queuedDL", "queuedUP" -> TorrentStatus.QUEUED
                "error", "missingFiles" -> TorrentStatus.ERROR
                else -> TorrentStatus.UNKNOWN
            },
            progress = progress.coerceIn(0f, 1f),
            downloadRate = dlspeed,
            uploadRate = upspeed,
            etaSeconds = eta.takeIf { it in 0 until INFINITE_ETA },
            sizeBytes = size,
            downloadedBytes = completed,
            uploadedBytes = uploaded,
            ratio = ratio.coerceAtLeast(0f),
            peersConnected = num_seeds + num_leechs,
            addedTimestamp = added_on.takeIf { it > 0 },
            downloadDir = save_path,
            error = if (state == "error" || state == "missingFiles") "Torrent in error state ($state)" else null,
            labels = buildList {
                if (category.isNotBlank()) add(category)
                tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            },
            // qBittorrent flags the metadata phase by state but reports no percentage
            metadataProgress = if (state == "metaDL" || state == "forcedMetaDL") 0f else null,
        )
    }

    @Serializable
    private data class FileInfo(
        val name: String,
        val size: Long = 0,
        val progress: Float = 0f,
        val priority: Int = 1,
        val index: Int? = null,
    )

    private companion object {
        /** qBittorrent reports 8640000 seconds as "no ETA". */
        const val INFINITE_ETA = 8640000L

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
        )
    }
}
