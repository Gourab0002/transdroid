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

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.transdroid.data.SettingsRepository
import org.transdroid.ui.TransdroidApp
import org.transdroid.ui.theme.TransdroidTheme

class MainActivity : ComponentActivity() {

    private var pendingTorrentUrl by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only read the launch intent on a fresh start; on recreation, restore the (possibly
        // already consumed) pending value so an added torrent isn't offered again on rotate
        pendingTorrentUrl = if (savedInstanceState == null) {
            extractTorrentUrl(intent)
        } else {
            savedInstanceState.getString(STATE_PENDING_TORRENT_URL)
        }
        setContent {
            val themeMode by appContainer.settingsRepository.themeMode.collectAsStateWithLifecycle(
                SettingsRepository.THEME_SYSTEM,
            )
            val dark = when (themeMode) {
                SettingsRepository.THEME_LIGHT -> false
                SettingsRepository.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            TransdroidTheme(darkTheme = dark) {
                val windowSizeClass = calculateWindowSizeClass(this)
                TransdroidApp(
                    useTwoPane = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
                    pendingTorrentUrl = pendingTorrentUrl,
                    onPendingTorrentUrlConsumed = { pendingTorrentUrl = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractTorrentUrl(intent)?.let { pendingTorrentUrl = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_TORRENT_URL, pendingTorrentUrl)
    }

    /** Pulls a magnet link, torrent URL or .torrent content URI out of VIEW/SEND intents. */
    private fun extractTorrentUrl(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> IncomingTorrent.fromView(intent.dataString)
        Intent.ACTION_SEND -> IncomingTorrent.fromSend(
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
            extraStreamUri = extraStreamUri(intent)?.toString(),
        )
        else -> null
    }

    private fun extraStreamUri(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private companion object {
        const val STATE_PENDING_TORRENT_URL = "pending_torrent_url"
    }
}
