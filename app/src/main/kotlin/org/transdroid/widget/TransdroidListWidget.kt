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
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.transdroid.MainActivity
import org.transdroid.R
import org.transdroid.appContainer
import org.transdroid.protocol.TorrentStatus
import org.transdroid.util.formatSpeed

/** Home screen widget: the most active torrents with their progress, tap to open the app. */
class TransdroidListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = context.appContainer.widgetStateRepository.current()
        val strings = Strings(
            appName = context.getString(R.string.app_name),
            noData = context.getString(R.string.widget_no_data),
            empty = context.getString(R.string.torrents_empty),
        )
        provideContent {
            GlanceTheme {
                WidgetContent(state, strings)
            }
        }
    }

    private data class Strings(val appName: String, val noData: String, val empty: String)

    @Composable
    private fun WidgetContent(state: WidgetState, strings: Strings) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.serverName ?: strings.appName,
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (state.updatedAtMillis != null) {
                    Text(
                        text = "↓ ${formatSpeed(state.downloadRate)}  ↑ ${formatSpeed(state.uploadRate)}",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                    )
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            when {
                state.updatedAtMillis == null -> Text(
                    text = strings.noData,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                )
                state.torrents.isEmpty() -> Text(
                    text = strings.empty,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                )
                else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(state.torrents) { torrent ->
                        TorrentRow(torrent)
                    }
                }
            }
        }
    }

    @Composable
    private fun TorrentRow(torrent: WidgetTorrent) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = torrent.name,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = " ${(torrent.progress * 100).toInt()}%",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
            Spacer(GlanceModifier.height(2.dp))
            LinearProgressIndicator(
                progress = torrent.progress,
                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                color = torrent.status.widgetColor(),
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
        }
    }

    /** Mirrors the in-app status accent colors (fixed values; Glance has no app theme). */
    private fun TorrentStatus.widgetColor(): androidx.glance.unit.ColorProvider = when (this) {
        TorrentStatus.DOWNLOADING -> ColorProvider(Color(0xFF1E88E5), Color(0xFF1E88E5))
        TorrentStatus.SEEDING -> ColorProvider(Color(0xFF66BB6A), Color(0xFF66BB6A))
        TorrentStatus.PAUSED -> ColorProvider(Color(0xFFAB47BC), Color(0xFFAB47BC))
        TorrentStatus.ERROR -> ColorProvider(Color(0xFFE57373), Color(0xFFE57373))
        else -> ColorProvider(Color(0xFF9E9E9E), Color(0xFF9E9E9E))
    }
}

class TransdroidListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TransdroidListWidget()
}
