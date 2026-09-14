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
package org.transdroid

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.transdroid.background.FinishedTorrentsWorker
import org.transdroid.background.RssCheckWorker
import org.transdroid.background.WidgetRefreshWorker
import org.transdroid.data.ServerProfile
import org.transdroid.discovery.LanDiscovery
import org.transdroid.data.ServerProfilesRepository
import org.transdroid.data.SettingsRepository
import org.transdroid.protocol.DaemonAdapter
import org.transdroid.protocol.DaemonAdapterFactory
import org.transdroid.protocol.rss.RssFetcher
import org.transdroid.widget.WidgetStateRepository

/** Lightweight manual dependency container; see the v3 plan's "keep DI light" decision. */
class AppContainer(context: Context) {

    val profilesRepository = ServerProfilesRepository(context)
    val settingsRepository = SettingsRepository(context)
    val widgetStateRepository = WidgetStateRepository(context)
    val lanDiscovery = LanDiscovery(context)

    val httpClient = DaemonAdapterFactory.defaultHttpClient()
    val rssFetcher = RssFetcher(httpClient)

    private val cachedAdapters = LinkedHashMap<String, Pair<ServerProfile, DaemonAdapter>>()

    /** The profile torrents are loaded from: the selected one, or the first configured. */
    val activeProfile: Flow<ServerProfile?> =
        combine(profilesRepository.profiles, settingsRepository.activeServerId) { profiles, activeId ->
            profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        }

    /** Server the widgets poll; falls back to the active profile. */
    val widgetProfile: Flow<ServerProfile?> =
        combine(profilesRepository.profiles, settingsRepository.widgetServerId, activeProfile) { profiles, widgetId, active ->
            profiles.firstOrNull { it.id == widgetId } ?: active
        }

    /**
     * Returns a cached adapter for [profile]; adapters keep session state like auth
     * cookies, so one entry per server avoids repeated logins when the UI and a
     * background worker use different servers. Bounded: the least-recently-used
     * server is dropped, and an entry is rebuilt when its profile was edited.
     */
    @Synchronized
    fun adapterFor(profile: ServerProfile): DaemonAdapter {
        cachedAdapters.remove(profile.id)?.let { (cachedProfile, adapter) ->
            if (cachedProfile == profile) {
                // Re-insert to mark most-recently-used
                cachedAdapters[profile.id] = cachedProfile to adapter
                return adapter
            }
        }
        if (cachedAdapters.size >= MAX_CACHED_ADAPTERS) {
            cachedAdapters.entries.firstOrNull()?.let { cachedAdapters.remove(it.key) }
        }
        val adapter = DaemonAdapterFactory.create(profile.toDaemonConfig(), httpClient)
        cachedAdapters[profile.id] = profile to adapter
        return adapter
    }

    /** An uncached adapter for testing yet-unsaved connection settings. */
    fun adapterForTest(profile: ServerProfile): DaemonAdapter =
        DaemonAdapterFactory.create(profile.toDaemonConfig(), httpClient)

    private companion object {
        const val MAX_CACHED_ADAPTERS = 4
    }
}

class TransdroidApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            WidgetRefreshWorker.schedule(this@TransdroidApplication)
            RssCheckWorker.schedule(this@TransdroidApplication)
            if (container.settingsRepository.notifyFinished.first()) {
                FinishedTorrentsWorker.schedule(this@TransdroidApplication)
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TransdroidApplication).container
