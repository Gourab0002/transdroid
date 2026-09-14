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
package org.transdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.background.FinishedTorrentsWorker
import org.transdroid.data.AppUpdate
import org.transdroid.data.BackupCrypto
import org.transdroid.data.ProfilesData
import org.transdroid.data.SearchProviderConfig
import org.transdroid.data.ServerProfile
import org.transdroid.data.SettingsRepository
import org.transdroid.data.UpdateChecker
import org.transdroid.protocol.CertificateFingerprint
import org.transdroid.protocol.DaemonCapability
import org.transdroid.protocol.SessionStats
import org.transdroid.protocol.Tls
import org.transdroid.protocol.discovery.DiscoveredDaemon
import org.transdroid.ui.torrents.UiError
import org.transdroid.ui.torrents.toUiError

sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data class Success(val versionInfo: String) : TestState()
    data class Failure(val error: UiError) : TestState()
}

sealed class CertificateState {
    data object Idle : CertificateState()
    data object Fetching : CertificateState()
    data class Fetched(val fingerprint: CertificateFingerprint) : CertificateState()
    data class Failed(val error: UiError) : CertificateState()
}

data class DiscoveryState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val found: List<DiscoveredDaemon> = emptyList(),
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = container.profilesRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeServerId: StateFlow<String?> = container.settingsRepository.activeServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    fun newProfileId(): String = UUID.randomUUID().toString()

    fun save(profile: ServerProfile) {
        viewModelScope.launch {
            // Read from the repository, not the StateFlow, which may not have emitted yet
            val firstServer = container.profilesRepository.profiles.first().isEmpty()
            container.profilesRepository.save(profile)
            if (firstServer) container.settingsRepository.setActiveServer(profile.id)
        }
    }

    fun delete(profileId: String) {
        viewModelScope.launch {
            val wasActive = container.settingsRepository.activeServerId.first() == profileId
            container.profilesRepository.delete(profileId)
            if (wasActive) container.settingsRepository.setActiveServer(null)
        }
    }

    fun setActive(profileId: String) {
        viewModelScope.launch { container.settingsRepository.setActiveServer(profileId) }
    }

    fun testConnection(profile: ServerProfile) {
        _testState.value = TestState.Testing
        viewModelScope.launch {
            _testState.value = try {
                TestState.Success(container.adapterForTest(profile).testConnection())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failure(e.toUiError(profile.host))
            }
        }
    }

    fun resetTestState() {
        _testState.value = TestState.Idle
        _certificateState.value = CertificateState.Idle
    }

    private val _certificateState = MutableStateFlow<CertificateState>(CertificateState.Idle)
    val certificateState: StateFlow<CertificateState> = _certificateState.asStateFlow()

    /** Reads the server's certificate fingerprint so the user can decide to trust it. */
    fun fetchCertificate(host: String, port: Int) {
        _certificateState.value = CertificateState.Fetching
        viewModelScope.launch {
            _certificateState.value = try {
                CertificateState.Fetched(Tls.fetchCertificate(host, port))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CertificateState.Failed(e.toUiError(host))
            }
        }
    }

    fun dismissCertificate() {
        _certificateState.value = CertificateState.Idle
    }

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    /** Scans the local network once per settings session; no-op while already scanning. */
    fun startLanScan() {
        if (_discovery.value.scanning) return
        _discovery.value = DiscoveryState(scanning = true)
        viewModelScope.launch {
            val found = try {
                container.lanDiscovery.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _discovery.value = DiscoveryState(scanning = false, scanned = true, found = found)
        }
    }

    val searchProviders: StateFlow<List<SearchProviderConfig>> = container.profilesRepository.searchProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveSearchProvider(provider: SearchProviderConfig) {
        viewModelScope.launch { container.profilesRepository.saveSearchProvider(provider) }
    }

    fun deleteSearchProvider(providerId: String) {
        viewModelScope.launch { container.profilesRepository.deleteSearchProvider(providerId) }
    }

    val notifyFinished: StateFlow<Boolean> = container.settingsRepository.notifyFinished
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val notifyRss: StateFlow<Boolean> = container.settingsRepository.notifyRss
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val themeMode: StateFlow<String> = container.settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.THEME_SYSTEM)

    val widgetServerId: StateFlow<String?> = container.settingsRepository.widgetServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _sessionStats = MutableStateFlow<SessionStats?>(null)
    val sessionStats: StateFlow<SessionStats?> = _sessionStats.asStateFlow()

    private val _sessionCapabilities = MutableStateFlow<Set<DaemonCapability>>(emptySet())
    val sessionCapabilities: StateFlow<Set<DaemonCapability>> = _sessionCapabilities.asStateFlow()

    private val _update = MutableStateFlow<AppUpdate?>(null)
    val availableUpdate: StateFlow<AppUpdate?> = _update.asStateFlow()

    fun setThemeMode(mode: String) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setWidgetServer(profileId: String?) {
        viewModelScope.launch { container.settingsRepository.setWidgetServer(profileId) }
    }

    fun setNotifyRss(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setNotifyRss(enabled) }
    }

    fun refreshSessionStats() {
        viewModelScope.launch {
            val profile = container.activeProfile.first() ?: return@launch
            try {
                val adapter = container.adapterFor(profile)
                _sessionCapabilities.value = adapter.capabilities
                if (DaemonCapability.SESSION_STATS in adapter.capabilities) {
                    _sessionStats.value = adapter.sessionStats()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun setGlobalSpeedLimits(downloadKbps: Long?, uploadKbps: Long?) {
        viewModelScope.launch {
            val profile = container.activeProfile.first() ?: return@launch
            try {
                val down = downloadKbps?.let { if (it <= 0) 0L else it * 1000 }
                val up = uploadKbps?.let { if (it <= 0) 0L else it * 1000 }
                container.adapterFor(profile).setGlobalSpeedLimits(down, up)
                refreshSessionStats()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun setAltSpeedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val profile = container.activeProfile.first() ?: return@launch
            try {
                container.adapterFor(profile).setAltSpeedEnabled(enabled)
                refreshSessionStats()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch {
            try {
                _update.value = UpdateChecker(container.httpClient).latest(currentVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _update.value = null
            }
        }
    }

    val pollIntervalSeconds: StateFlow<Int> = container.settingsRepository.pollIntervalSeconds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            org.transdroid.data.SettingsRepository.DEFAULT_POLL_INTERVAL_SECONDS,
        )

    fun setPollInterval(seconds: Int) {
        viewModelScope.launch { container.settingsRepository.setPollIntervalSeconds(seconds) }
    }

    /** Persists the toggle and (un)schedules the background check accordingly. */
    fun setNotifyFinished(context: android.content.Context, enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setNotifyFinished(enabled)
            if (enabled) {
                FinishedTorrentsWorker.schedule(context.applicationContext)
            } else {
                FinishedTorrentsWorker.cancel(context.applicationContext)
            }
        }
    }

    /** Held between encrypting a backup and the document-picker callback; not in instance state. */
    var pendingBackupBytes: ByteArray? = null
        private set

    fun takePendingBackup(): ByteArray? = pendingBackupBytes.also { pendingBackupBytes = null }

    /** Serializes and encrypts the whole settings store with the given passphrase. */
    fun createBackup(passphrase: String, onReady: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val data = container.profilesRepository.currentData()
                val chars = passphrase.toCharArray()
                val bytes = try {
                    withContext(Dispatchers.Default) {
                        BackupCrypto.encrypt(
                            backupJson.encodeToString(ProfilesData.serializer(), data).encodeToByteArray(),
                            chars,
                        )
                    }
                } finally {
                    chars.fill('\u0000')
                }
                pendingBackupBytes = bytes
                onReady(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingBackupBytes = null
                onReady(false)
            }
        }
    }

    fun restoreBackup(bytes: ByteArray, passphrase: String, onResult: (RestoreResult) -> Unit) {
        viewModelScope.launch {
            val result = try {
                val plaintext = withContext(Dispatchers.Default) {
                    BackupCrypto.decrypt(bytes, passphrase.toCharArray())
                }
                val data = backupJson.decodeFromString(ProfilesData.serializer(), plaintext.decodeToString())
                container.profilesRepository.replaceData(data)
                // The restored profiles carry their own ids; reset the selection if stale
                val activeId = container.settingsRepository.activeServerId.first()
                if (data.profiles.none { it.id == activeId }) {
                    container.settingsRepository.setActiveServer(data.profiles.firstOrNull()?.id)
                }
                RestoreResult.Success(data.profiles.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AEADBadTagException) {
                RestoreResult.WrongPassphrase
            } catch (e: Exception) {
                RestoreResult.InvalidFile
            }
            onResult(result)
        }
    }

    sealed class RestoreResult {
        data class Success(val serverCount: Int) : RestoreResult()
        data object WrongPassphrase : RestoreResult()
        data object InvalidFile : RestoreResult()
    }

    companion object {
        private val backupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
