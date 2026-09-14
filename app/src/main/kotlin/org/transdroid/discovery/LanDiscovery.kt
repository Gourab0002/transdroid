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
package org.transdroid.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.transdroid.protocol.discovery.DaemonProbe
import org.transdroid.protocol.discovery.DiscoveredDaemon

/**
 * Finds torrent daemons on the local Wi-Fi/Ethernet subnet: a fast TCP connect sweep of
 * the /24 around the device's own address on the clients' default ports, followed by an
 * HTTP probe of every open port to identify which daemon answers.
 */
class LanDiscovery(private val context: Context) {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** Empty when not on a local network (cellular-only) or nothing was found. */
    suspend fun scan(): List<DiscoveredDaemon> = withContext(Dispatchers.IO) {
        val linkAddress = localLinkAddress() ?: return@withContext emptyList()
        val targets = scanTargets(linkAddress.address as Inet4Address, linkAddress.prefixLength)
        val concurrency = Semaphore(CONCURRENT_SOCKETS)
        coroutineScope {
            targets.flatMap { host ->
                DaemonProbe.DEFAULT_PORTS.map { port ->
                    async {
                        concurrency.withPermit {
                            if (isPortOpen(host, port)) DaemonProbe.probe(probeClient, host, port) else null
                        }
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.host to it.port }.sortedBy { it.host }
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** The device's own IPv4 link address on Wi-Fi, Ethernet or VPN, if any. */
    private fun localLinkAddress(): LinkAddress? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (!local) return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 300
        const val CONCURRENT_SOCKETS = 96
    }
}

/**
 * Host addresses to probe for [address]/[prefixLength]. Subnets between /24 and /30 are
 * swept in full; anything larger (a /16 office LAN) would take far too long, and a /31
 * or /32 assignment (typical for VPNs like Tailscale) describes no sweepable range at
 * all — in both cases only the /24 around the device is covered. Capped defensively.
 */
internal fun scanTargets(address: Inet4Address, prefixLength: Int): List<String> {
    val ip = address.toIntValue()
    val hosts = if (prefixLength in 24..30) {
        subnetHosts(ip, prefixLength)
    } else {
        subnetHosts(ip, 24)
    }
    return hosts.take(MAX_SCAN_TARGETS)
}

private const val MAX_SCAN_TARGETS = 1024

private fun subnetHosts(ip: Int, prefixLength: Int): List<String> {
    val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
    val network = ip and mask
    val broadcast = network or mask.inv()
    return ((network + 1) until broadcast).map { it.toIpString() }
}

private fun Inet4Address.toIntValue(): Int {
    val b = address
    return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}

private fun Int.toIpString(): String =
    "${(this ushr 24) and 0xFF}.${(this ushr 16) and 0xFF}.${(this ushr 8) and 0xFF}.${this and 0xFF}"
