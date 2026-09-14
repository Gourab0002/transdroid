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
package org.transdroid.ui.torrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentStatus

class TorrentFilterTest {

    private fun torrent(
        id: String,
        name: String,
        status: TorrentStatus,
        added: Long? = 1,
        downloadRate: Long = 0,
        ratio: Float = 0f,
        labels: List<String> = emptyList(),
    ) = Torrent(
        id = id,
        name = name,
        status = status,
        progress = 0.5f,
        downloadRate = downloadRate,
        uploadRate = 0,
        sizeBytes = 100,
        downloadedBytes = 50,
        uploadedBytes = 0,
        ratio = ratio,
        addedTimestamp = added,
        labels = labels,
    )

    @Test
    fun `downloading filter includes queued and checking`() {
        assertTrue(TorrentFilter.DOWNLOADING.matches(torrent("1", "a", TorrentStatus.QUEUED)))
        assertTrue(TorrentFilter.DOWNLOADING.matches(torrent("1", "a", TorrentStatus.CHECKING)))
    }

    @Test
    fun `visible torrents apply filter sort and name`() {
        val state = TorrentsUiState(
            torrents = listOf(
                torrent("1", "Beta", TorrentStatus.SEEDING, added = 10, ratio = 2f),
                torrent("2", "Alpha", TorrentStatus.DOWNLOADING, added = 20, downloadRate = 100),
                torrent("3", "Gamma", TorrentStatus.PAUSED, added = 30, labels = listOf("tv")),
            ),
            filter = TorrentFilter.ALL,
            sort = TorrentSort.NAME,
            nameFilter = "a",
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), state.visibleTorrents.map { it.name })
    }

    @Test
    fun `label filter keeps matching torrents`() {
        val state = TorrentsUiState(
            torrents = listOf(
                torrent("1", "A", TorrentStatus.SEEDING, labels = listOf("tv")),
                torrent("2", "B", TorrentStatus.SEEDING, labels = listOf("movies")),
            ),
            labelFilter = "tv",
        )
        assertEquals(listOf("A"), state.visibleTorrents.map { it.name })
    }
}
