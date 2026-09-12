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
package org.transdroid.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.flow.first
import org.transdroid.appContainer

val TorrentIdParam = ActionParameters.Key<String>("torrent_id")
val TorrentPausedParam = ActionParameters.Key<Boolean>("torrent_paused")

/** Refreshes the widget snapshot straight from the active daemon. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        refreshSnapshot(context)
    }
}

/** Starts a paused torrent or pauses a running one, then refreshes the snapshot. */
class ToggleTorrentAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val torrentId = parameters[TorrentIdParam] ?: return
        val paused = parameters[TorrentPausedParam] ?: return
        val container = context.appContainer
        val profile = container.activeProfile.first() ?: return
        try {
            val adapter = container.adapterFor(profile)
            if (paused) adapter.start(torrentId) else adapter.pause(torrentId)
        } catch (e: Exception) {
            // Widget taps have no error surface; the refresh below shows the real state
        }
        refreshSnapshot(context)
    }
}

private suspend fun refreshSnapshot(context: Context) {
    val container = context.appContainer
    val profile = container.activeProfile.first() ?: return
    try {
        val torrents = container.adapterFor(profile).listTorrents()
        container.widgetStateRepository.update(profile.displayName, torrents)
    } catch (e: Exception) {
        // Unreachable server: keep showing the last snapshot rather than blanking out
    }
}
