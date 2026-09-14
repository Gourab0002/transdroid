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

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryTest {

    private fun v4(host: String) = InetAddress.getByName(host) as Inet4Address

    @Test
    fun `a slash-24 is swept in full minus network and broadcast`() {
        val targets = scanTargets(v4("192.168.1.50"), 24)

        assertEquals(254, targets.size)
        assertEquals("192.168.1.1", targets.first())
        assertEquals("192.168.1.254", targets.last())
        assertTrue(targets.none { it.endsWith(".0") || it.endsWith(".255") })
    }

    @Test
    fun `a smaller subnet is swept exactly`() {
        val targets = scanTargets(v4("192.168.1.130"), 25)

        assertEquals(126, targets.size)
        assertEquals("192.168.1.129", targets.first())
        assertEquals("192.168.1.254", targets.last())
    }

    @Test
    fun `a larger subnet falls back to the surrounding slash-24`() {
        val targets = scanTargets(v4("10.0.5.7"), 16)

        assertEquals(254, targets.size)
        assertEquals("10.0.5.1", targets.first())
        assertEquals("10.0.5.254", targets.last())
    }

    @Test
    fun `a slash-32 vpn assignment falls back to the surrounding slash-24`() {
        val targets = scanTargets(v4("100.64.0.7"), 32)

        assertEquals(254, targets.size)
        assertEquals("100.64.0.1", targets.first())
        assertEquals("100.64.0.254", targets.last())
    }
}
