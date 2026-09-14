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
package org.transdroid.protocol.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.transdroid.protocol.DaemonType
import org.transdroid.protocol.rtorrent.XmlRpc

/** A torrent daemon found on the local network. */
data class DiscoveredDaemon(
    val type: DaemonType,
    val host: String,
    val port: Int,
)

/**
 * Identifies which torrent daemon (if any) answers on a host/port by probing each
 * client's characteristic HTTP endpoint. Only plain HTTP is probed — discovery targets
 * LAN default setups; HTTPS/self-signed servers are added manually.
 */
object DaemonProbe {

    /** Ports worth scanning: Transmission, qBittorrent, Deluge Web UI, and rTorrent RPC defaults. */
    val DEFAULT_PORTS: List<Int> = listOf(9091, 8080, 8112, 5000)

    suspend fun probe(httpClient: OkHttpClient, host: String, port: Int): DiscoveredDaemon? {
        probeTransmission(httpClient, host, port)?.let { return it }
        probeQbittorrent(httpClient, host, port)?.let { return it }
        probeDeluge(httpClient, host, port)?.let { return it }
        probeRtorrent(httpClient, host, port)?.let { return it }
        return null
    }

    private fun origin(host: String, port: Int): String =
        HttpUrl.Builder().scheme("http").host(host.removeSurrounding("[", "]")).port(port).build()
            .toString().trimEnd('/')

    private suspend fun probeTransmission(client: OkHttpClient, host: String, port: Int): DiscoveredDaemon? =
        tryRequest(client, Request.Builder().url("${origin(host, port)}/transmission/rpc").get().build()) { response ->
            val challenged = response.code == 409 && response.header("X-Transmission-Session-Id") != null
            val authWalled = response.code == 401
            if (challenged || authWalled) DiscoveredDaemon(DaemonType.TRANSMISSION, host, port) else null
        }

    private suspend fun probeQbittorrent(client: OkHttpClient, host: String, port: Int): DiscoveredDaemon? =
        tryRequest(client, Request.Builder().url("${origin(host, port)}/api/v2/app/webapiVersion").get().build()) { response ->
            val body = if (response.code == 200 || response.code == 403) response.body?.string().orEmpty() else ""
            val versionLike = response.code == 200 && body.length in 1..16 &&
                body.trim().firstOrNull()?.isDigit() == true
            // 403 means the endpoint exists but wants the SID cookie first; HTML 403s (Cloudflare)
            // are not qBittorrent
            val cookieWalled = response.code == 403 && !body.trimStart().startsWith("<")
            if (versionLike || cookieWalled) DiscoveredDaemon(DaemonType.QBITTORRENT, host, port) else null
        }

    private suspend fun probeDeluge(client: OkHttpClient, host: String, port: Int): DiscoveredDaemon? {
        val body = """{"method":"web.connected","params":[],"id":1}"""
        val request = Request.Builder()
            .url("${origin(host, port)}/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return tryRequest(client, request) { response ->
            val text = if (response.code == 200) response.body?.string().orEmpty() else ""
            if (text.contains("\"result\"") && text.contains("\"error\"")) {
                DiscoveredDaemon(DaemonType.DELUGE, host, port)
            } else {
                null
            }
        }
    }

    private suspend fun probeRtorrent(client: OkHttpClient, host: String, port: Int): DiscoveredDaemon? {
        val payload = XmlRpc.buildRequest("system.client_version", emptyList())
        val request = Request.Builder()
            .url("${origin(host, port)}/RPC2")
            .post(payload.toRequestBody("text/xml".toMediaType()))
            .build()
        return tryRequest(client, request) { response ->
            val text = response.body?.string().orEmpty()
            val xmlRpc = text.contains("methodResponse") || text.contains("fault")
            if ((response.code == 200 && xmlRpc) || response.code == 401) {
                DiscoveredDaemon(DaemonType.RTORRENT, host, port)
            } else {
                null
            }
        }
    }

    /**
     * One misbehaving LAN device must never abort the whole scan, so any failure —
     * transport or a response too strange to parse — just means "not this daemon".
     */
    private suspend fun tryRequest(
        client: OkHttpClient,
        request: Request,
        handle: (okhttp3.Response) -> DiscoveredDaemon?,
    ): DiscoveredDaemon? = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use(handle)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
