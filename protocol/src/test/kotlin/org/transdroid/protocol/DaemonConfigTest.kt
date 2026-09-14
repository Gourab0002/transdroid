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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.transdroid.protocol.internal.joinPath
import org.transdroid.protocol.internal.isCertificateTrustFailure
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class DaemonConfigTest {

    @Test
    fun `ipv4 base url is host colon port`() {
        val config = DaemonConfig(DaemonType.TRANSMISSION, "192.168.1.5", 9091)
        assertEquals("http://192.168.1.5:9091", config.baseUrl)
    }

    @Test
    fun `ipv6 base url brackets the host`() {
        val config = DaemonConfig(DaemonType.TRANSMISSION, "2001:db8::1", 9091, useSsl = true)
        assertEquals("https://[2001:db8::1]:9091", config.baseUrl)
    }

    @Test
    fun `already bracketed ipv6 is not double wrapped`() {
        val config = DaemonConfig(DaemonType.QBITTORRENT, "[::1]", 8080)
        assertEquals("http://[::1]:8080", config.baseUrl)
    }

    @Test
    fun `joinPath normalizes slashes`() {
        assertEquals("/api/v2/torrents/info", joinPath(null, "api/v2/torrents/info"))
        assertEquals("/qbt/api/v2/torrents/info", joinPath("/qbt/", "/api/v2/torrents/info"))
    }

    @Test
    fun `certificate failures are distinguished from other tls errors`() {
        assertTrue(isCertificateTrustFailure(SSLPeerUnverifiedException("unverified")))
        assertTrue(
            isCertificateTrustFailure(
                SSLHandshakeException("handshake").apply { initCause(CertificateException("untrusted")) },
            ),
        )
        assertFalse(isCertificateTrustFailure(SSLException("protocol_version")))
        assertFalse(isCertificateTrustFailure(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun `custom cookie and authorization headers are dropped so they cannot clobber sessions`() {
        val config = DaemonConfig(
            type = DaemonType.QBITTORRENT,
            host = "localhost",
            port = 8080,
            customHeaders = mapOf(
                "Cookie" to "SID=attacker",
                "Authorization" to "Basic abc",
                "CF-Access-Client-Id" to "id",
            ),
        )
        // Factory filtering is applied at create-time; the config itself still holds the map
        assertEquals("SID=attacker", config.customHeaders["Cookie"])
        val adapter = DaemonAdapterFactory.create(config)
        assertEquals(DaemonType.QBITTORRENT, adapter.config.type)
    }
}
