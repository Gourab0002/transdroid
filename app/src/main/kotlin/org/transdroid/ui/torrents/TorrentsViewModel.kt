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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.data.ProfilesReadError
import org.transdroid.data.ServerProfile
import org.transdroid.protocol.AddOptions
import org.transdroid.protocol.DaemonCapability
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.QueueMove
import org.transdroid.protocol.SessionStats
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentPeer
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.TorrentTracker

/** User-facing error kinds; mapped to localized strings in the UI layer. */
sealed class UiError {
    data class Connection(val host: String) : UiError()
    data object Authentication : UiError()
    data object Ssl : UiError()
    data class Unexpected(val detail: String? = null) : UiError()
    data object Unsupported : UiError()
}

internal fun Throwable.toUiError(host: String): UiError = when (this) {
    is DaemonException.Connection -> UiError.Connection(host)
    is DaemonException.Authentication -> UiError.Authentication
    is DaemonException.UntrustedServer -> UiError.Ssl
    is DaemonException.Unsupported -> UiError.Unsupported
    is DaemonException.UnexpectedResponse -> UiError.Unexpected()
    else -> UiError.Unexpected()
}

enum class TorrentFilter {
    ALL, DOWNLOADING, SEEDING, PAUSED, CHECKING, QUEUED, ERROR;

    fun matches(torrent: Torrent): Boolean = when (this) {
        ALL -> true
        DOWNLOADING -> torrent.status == TorrentStatus.DOWNLOADING
        SEEDING -> torrent.status == TorrentStatus.SEEDING
        PAUSED -> torrent.status == TorrentStatus.PAUSED
        CHECKING -> torrent.status == TorrentStatus.CHECKING
        QUEUED -> torrent.status == TorrentStatus.QUEUED
        ERROR -> torrent.status == TorrentStatus.ERROR
    }
}

enum class TorrentSort {
    DATE_ADDED, NAME, DOWNLOAD_SPEED, RATIO;

    fun comparator(): Comparator<Torrent> = when (this) {
        DATE_ADDED -> compareByDescending { it.addedTimestamp ?: Long.MIN_VALUE }
        NAME -> compareBy { it.name.lowercase() }
        DOWNLOAD_SPEED -> compareByDescending { it.downloadRate }
        RATIO -> compareByDescending { it.ratio }
    }
}

data class TorrentsUiState(
    val activeProfile: ServerProfile? = null,
    /** False until the profile store has emitted, so we don't flash the welcome screen. */
    val profilesLoaded: Boolean = false,
    /** Number of configured servers; single-server flows can skip confirmation steps. */
    val profileCount: Int = 0,
    val profiles: List<ServerProfile> = emptyList(),
    val profilesReadError: ProfilesReadError? = null,
    val torrents: List<Torrent> = emptyList(),
    /** True after the first successful load for the active profile. */
    val hasLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    val filter: TorrentFilter = TorrentFilter.ALL,
    val labelFilter: String? = null,
    val nameFilter: String = "",
    val sort: TorrentSort = TorrentSort.DATE_ADDED,
    val selectedTorrentId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val files: Map<String, List<TorrentFile>> = emptyMap(),
    val filesError: UiError? = null,
    val trackers: Map<String, List<TorrentTracker>> = emptyMap(),
    val peers: Map<String, List<TorrentPeer>> = emptyMap(),
    val capabilities: Set<DaemonCapability> = emptySet(),
    val sessionStats: SessionStats? = null,
) {
    val availableLabels: List<String>
        get() = torrents.flatMap { it.labels }.distinct().sorted()

    val totalDownloadRate: Long
        get() = sessionStats?.downloadRate ?: torrents.sumOf { it.downloadRate }

    val totalUploadRate: Long
        get() = sessionStats?.uploadRate ?: torrents.sumOf { it.uploadRate }

    val visibleTorrents: List<Torrent>
        get() = torrents
            .filter {
                filter.matches(it) &&
                    (labelFilter == null || labelFilter in it.labels) &&
                    (nameFilter.isBlank() || it.name.contains(nameFilter.trim(), ignoreCase = true))
            }
            .sortedWith(sort.comparator().thenBy { it.name.lowercase() })

    val selectedTorrent: Torrent?
        get() = torrents.firstOrNull { it.id == selectedTorrentId }

    val selecting: Boolean
        get() = selectedIds.isNotEmpty()
}

class TorrentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(TorrentsUiState())
    val ui: StateFlow<TorrentsUiState> = _ui.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        viewModelScope.launch {
            combine(
                combine(
                    container.activeProfile,
                    container.profilesRepository.profiles,
                    container.profilesRepository.readError,
                    container.settingsRepository.torrentFilter,
                    container.settingsRepository.torrentSort,
                ) { active, all, readError, filterName, sortName ->
                    Combined(active, all, readError, filterName, sortName)
                },
                container.settingsRepository.labelFilter,
            ) { snapshot, label -> snapshot to label }.collect { (snapshot, label) ->
                _ui.update { state ->
                    val switched = snapshot.active?.id != state.activeProfile?.id
                    state.copy(
                        activeProfile = snapshot.active,
                        profilesLoaded = true,
                        profileCount = snapshot.all.size,
                        profiles = snapshot.all,
                        profilesReadError = snapshot.readError,
                        torrents = if (switched) emptyList() else state.torrents,
                        hasLoaded = if (switched) false else state.hasLoaded,
                        files = if (switched) emptyMap() else state.files,
                        trackers = if (switched) emptyMap() else state.trackers,
                        peers = if (switched) emptyMap() else state.peers,
                        error = if (switched) null else state.error,
                        selectedIds = if (switched) emptySet() else state.selectedIds,
                        capabilities = if (switched) emptySet() else state.capabilities,
                        sessionStats = if (switched) null else state.sessionStats,
                        filter = TorrentFilter.entries.find { it.name == snapshot.filterName } ?: TorrentFilter.ALL,
                        sort = TorrentSort.entries.find { it.name == snapshot.sortName } ?: TorrentSort.DATE_ADDED,
                        labelFilter = label,
                    )
                }
                if (snapshot.active != null) refresh(showSpinner = false)
            }
        }
    }

    private data class Combined(
        val active: ServerProfile?,
        val all: List<ServerProfile>,
        val readError: ProfilesReadError?,
        val filterName: String,
        val sortName: String,
    )

    /** Runs while the torrents UI is in the foreground; cancellation stops the polling. */
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            refreshNow(showSpinner = false)
            val selectedId = _ui.value.selectedTorrentId
            if (selectedId != null) {
                loadFilesNow(selectedId)
                loadTrackersAndPeers(selectedId)
            }
            delay(container.settingsRepository.pollIntervalSeconds.first() * 1000L)
        }
    }

    fun refresh(showSpinner: Boolean = true) {
        viewModelScope.launch { refreshNow(showSpinner) }
    }

    private suspend fun refreshNow(showSpinner: Boolean) {
        val profile = _ui.value.activeProfile ?: return
        refreshMutex.withLock {
            if (showSpinner) _ui.update { it.copy(refreshing = true) }
            try {
                val adapter = container.adapterFor(profile)
                val torrents = adapter.listTorrents()
                val stats = if (DaemonCapability.SESSION_STATS in adapter.capabilities) {
                    try {
                        adapter.sessionStats()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                _ui.update {
                    if (it.activeProfile?.id != profile.id) it
                    else it.copy(
                        torrents = torrents,
                        hasLoaded = true,
                        refreshing = false,
                        error = null,
                        capabilities = adapter.capabilities,
                        sessionStats = stats,
                        selectedIds = it.selectedIds.intersect(torrents.map { torrent -> torrent.id }.toSet()),
                    )
                }
                container.widgetStateRepository.update(profile.displayName, torrents)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    if (it.activeProfile?.id != profile.id) it
                    else it.copy(refreshing = false, error = e.toUiError(profile.host))
                }
            }
        }
    }

    fun setFilter(filter: TorrentFilter) {
        _ui.update { it.copy(filter = filter) }
        viewModelScope.launch { container.settingsRepository.setTorrentFilter(filter.name) }
    }

    fun setSort(sort: TorrentSort) {
        _ui.update { it.copy(sort = sort) }
        viewModelScope.launch { container.settingsRepository.setTorrentSort(sort.name) }
    }

    fun setLabelFilter(label: String?) {
        val next = if (_ui.value.labelFilter == label) null else label
        _ui.update { it.copy(labelFilter = next) }
        viewModelScope.launch { container.settingsRepository.setLabelFilter(next) }
    }

    fun setNameFilter(query: String) {
        _ui.update { it.copy(nameFilter = query) }
    }

    fun setActiveServer(profileId: String) {
        viewModelScope.launch { container.settingsRepository.setActiveServer(profileId) }
    }

    fun setFilePriority(torrentId: String, file: TorrentFile, priority: FilePriority) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val adapter = container.adapterFor(profile)
                adapter.setFilePriority(torrentId, file.index, priority)
                val files = adapter.listFiles(torrentId)
                _ui.update { it.copy(files = it.files + (torrentId to files), filesError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.toUiError(profile.host)) }
            }
        }
    }

    fun select(torrentId: String?) {
        _ui.update { it.copy(selectedTorrentId = torrentId, selectedIds = emptySet()) }
        if (torrentId != null) {
            loadFiles(torrentId)
            viewModelScope.launch { loadTrackersAndPeers(torrentId) }
        }
    }

    fun toggleSelection(torrentId: String) {
        _ui.update { state ->
            val next = if (torrentId in state.selectedIds) state.selectedIds - torrentId else state.selectedIds + torrentId
            state.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        _ui.update { it.copy(selectedIds = emptySet()) }
    }

    fun toggleStartPause(torrent: Torrent) {
        runAction { adapter ->
            if (torrent.status == TorrentStatus.PAUSED) adapter.start(torrent.id) else adapter.pause(torrent.id)
        }
    }

    fun startSelected() {
        val ids = _ui.value.selectedIds.toList()
        runAction { adapter -> adapter.start(ids) }
        clearSelection()
    }

    fun pauseSelected() {
        val ids = _ui.value.selectedIds.toList()
        runAction { adapter -> adapter.pause(ids) }
        clearSelection()
    }

    fun removeSelected(deleteData: Boolean) {
        val ids = _ui.value.selectedIds.toList()
        runAction { adapter -> adapter.remove(ids, deleteData) }
        clearSelection()
    }

    fun remove(torrent: Torrent, deleteData: Boolean) {
        runAction { adapter -> adapter.remove(torrent.id, deleteData) }
        _ui.update {
            if (it.selectedTorrentId == torrent.id) it.copy(selectedTorrentId = null) else it
        }
    }

    fun recheck(torrentId: String) {
        runAction { adapter -> adapter.recheck(torrentId) }
    }

    fun reannounce(torrentId: String) {
        runAction { adapter -> adapter.reannounce(torrentId) }
    }

    fun forceStart(torrentId: String) {
        runAction { adapter -> adapter.forceStart(torrentId) }
    }

    fun moveQueue(torrentId: String, move: QueueMove) {
        runAction { adapter -> adapter.moveQueue(torrentId, move) }
    }

    fun setLabels(torrentId: String, labels: List<String>) {
        runAction { adapter -> adapter.setLabels(torrentId, labels) }
    }

    fun setLocation(torrentId: String, path: String, moveData: Boolean) {
        runAction { adapter -> adapter.setLocation(torrentId, path, moveData) }
    }

    fun setTorrentSpeedLimits(torrentId: String, downloadBytesPerSec: Long?, uploadBytesPerSec: Long?) {
        runAction { adapter -> adapter.setTorrentSpeedLimits(torrentId, downloadBytesPerSec, uploadBytesPerSec) }
    }

    fun setGlobalSpeedLimits(downloadBytesPerSec: Long?, uploadBytesPerSec: Long?) {
        runAction { adapter -> adapter.setGlobalSpeedLimits(downloadBytesPerSec, uploadBytesPerSec) }
    }

    fun setAltSpeedEnabled(enabled: Boolean) {
        runAction { adapter -> adapter.setAltSpeedEnabled(enabled) }
    }

    fun loadFiles(torrentId: String) {
        viewModelScope.launch { loadFilesNow(torrentId) }
    }

    private suspend fun loadTrackersAndPeers(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        val adapter = container.adapterFor(profile)
        if (DaemonCapability.TRACKERS in adapter.capabilities) {
            try {
                val trackers = adapter.listTrackers(torrentId)
                _ui.update { it.copy(trackers = it.trackers + (torrentId to trackers)) }
            } catch (_: Exception) {
            }
        }
        if (DaemonCapability.PEERS in adapter.capabilities) {
            try {
                val peers = adapter.listPeers(torrentId)
                _ui.update { it.copy(peers = it.peers + (torrentId to peers)) }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun loadFilesNow(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        try {
            val files = container.adapterFor(profile).listFiles(torrentId)
            _ui.update { it.copy(files = it.files + (torrentId to files), filesError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(filesError = e.toUiError(profile.host)) }
        }
    }

    /** Adds a torrent by magnet/URL; invokes [onResult] with null on success. */
    fun add(url: String, startPaused: Boolean = false, onResult: (UiError?) -> Unit) {
        add(url, AddOptions(startPaused = startPaused), onResult)
    }

    fun add(url: String, options: AddOptions, onResult: (UiError?) -> Unit) {
        addTo(_ui.value.activeProfile, url, options, onResult)
    }

    fun addTo(profile: ServerProfile?, url: String, options: AddOptions, onResult: (UiError?) -> Unit) {
        if (profile == null) return
        viewModelScope.launch {
            try {
                if (profile.id != _ui.value.activeProfile?.id) {
                    container.settingsRepository.setActiveServer(profile.id)
                }
                container.adapterFor(profile).addByUrl(url, options)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.toUiError(profile.host))
            }
        }
    }

    /** Adds a torrent from the raw contents of a .torrent file. */
    fun addFile(fileName: String, contents: ByteArray, startPaused: Boolean = false, onResult: (UiError?) -> Unit) {
        addFile(fileName, contents, AddOptions(startPaused = startPaused), onResult)
    }

    fun addFile(fileName: String, contents: ByteArray, options: AddOptions, onResult: (UiError?) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).addByFile(fileName, contents, options)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.toUiError(profile.host))
            }
        }
    }

    private fun runAction(action: suspend (org.transdroid.protocol.DaemonAdapter) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                action(container.adapterFor(profile))
                refreshNow(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.toUiError(profile.host)) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TorrentsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
