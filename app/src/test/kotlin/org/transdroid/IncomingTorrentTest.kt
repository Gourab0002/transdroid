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
package org.transdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingTorrentTest {

    @Test
    fun `view accepts magnet content file and http torrent urls`() {
        assertEquals("magnet:?xt=urn:btih:abc", IncomingTorrent.fromView("magnet:?xt=urn:btih:abc"))
        assertEquals("content://downloads/file.torrent", IncomingTorrent.fromView("content://downloads/file.torrent"))
        assertEquals("https://example.com/a.torrent", IncomingTorrent.fromView("https://example.com/a.torrent"))
        assertNull(IncomingTorrent.fromView("ftp://example.com/a.torrent"))
    }

    @Test
    fun `send prefers stream uri over extra text`() {
        assertEquals(
            "content://media/torrent",
            IncomingTorrent.fromSend("https://example.com/a.torrent", "content://media/torrent"),
        )
        assertEquals(
            "magnet:?xt=urn:btih:abc",
            IncomingTorrent.fromSend(" magnet:?xt=urn:btih:abc ", null),
        )
        assertNull(IncomingTorrent.fromSend("hello world", null))
    }
}
