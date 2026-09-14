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

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.transdroid.BuildConfig
import org.transdroid.R
import org.transdroid.data.SearchProviderConfig
import org.transdroid.data.SettingsRepository
import org.transdroid.protocol.SessionStats
import org.transdroid.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onEditServer: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeServerId.collectAsStateWithLifecycle()
    val providers by viewModel.searchProviders.collectAsStateWithLifecycle()
    val notifyFinished by viewModel.notifyFinished.collectAsStateWithLifecycle()
    val notifyRss by viewModel.notifyRss.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val widgetServerId by viewModel.widgetServerId.collectAsStateWithLifecycle()
    val sessionStats by viewModel.sessionStats.collectAsStateWithLifecycle()
    val sessionCapabilities by viewModel.sessionCapabilities.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchAvailable = booleanResource(R.bool.search_available)
    val rssAvailable = booleanResource(R.bool.rss_available)
    val updateCheckAvailable = booleanResource(R.bool.updatecheck_available)

    LaunchedEffect(Unit) {
        viewModel.refreshSessionStats()
        if (updateCheckAvailable) viewModel.checkForUpdate(org.transdroid.BuildConfig.VERSION_NAME)
    }

    var editingProvider by remember { mutableStateOf<SearchProviderConfig?>(null) }
    var showProviderDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val exportWrittenMessage = stringResource(R.string.backup_export_done)
    val exportFailedMessage = stringResource(R.string.backup_export_failed)
    val restoreWrongPassphrase = stringResource(R.string.backup_wrong_passphrase)
    val restoreInvalid = stringResource(R.string.backup_invalid_file)

    val exportCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = viewModel.takePendingBackup()
        if (uri != null) {
            scope.launch {
                val ok = bytes != null && withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                    } catch (e: Exception) {
                        false
                    }
                }
                snackbarHostState.showSnackbar(if (ok) exportWrittenMessage else exportFailedMessage)
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setNotifyFinished(context, granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.details_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditServer(null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_add_server))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.settings_servers)) }
            items(profiles, key = { it.id }) { profile ->
                val effectiveActiveId = activeId ?: profiles.firstOrNull()?.id
                ListItem(
                    headlineContent = { Text(profile.displayName) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.settings_server_summary,
                                stringResource(profile.type.displayNameRes()),
                                profile.host,
                                profile.port,
                            )
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        RadioButton(
                            selected = profile.id == effectiveActiveId,
                            onClick = { viewModel.setActive(profile.id) },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditServer(profile.id) },
                )
            }

            item {
                val pollInterval by viewModel.pollIntervalSeconds.collectAsStateWithLifecycle()
                var intervalMenuOpen by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_poll_interval)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.settings_poll_interval_value, pollInterval, pollInterval)) },
                    leadingContent = {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.fillMaxWidth().clickable { intervalMenuOpen = true },
                )
                DropdownMenu(expanded = intervalMenuOpen, onDismissRequest = { intervalMenuOpen = false }) {
                    SettingsRepository.POLL_INTERVAL_OPTIONS.forEach { seconds ->
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.settings_poll_interval_value, seconds, seconds)) },
                            leadingIcon = { RadioButton(selected = seconds == pollInterval, onClick = null) },
                            onClick = {
                                viewModel.setPollInterval(seconds)
                                intervalMenuOpen = false
                            },
                        )
                    }
                }
            }

            item {
                var themeMenuOpen by remember { mutableStateOf(false) }
                val themeLabel = when (themeMode) {
                    SettingsRepository.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
                    SettingsRepository.THEME_DARK -> stringResource(R.string.settings_theme_dark)
                    else -> stringResource(R.string.settings_theme_system)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_theme)) },
                    supportingContent = { Text(themeLabel) },
                    modifier = Modifier.fillMaxWidth().clickable { themeMenuOpen = true },
                )
                DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                    listOf(
                        SettingsRepository.THEME_SYSTEM to R.string.settings_theme_system,
                        SettingsRepository.THEME_LIGHT to R.string.settings_theme_light,
                        SettingsRepository.THEME_DARK to R.string.settings_theme_dark,
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = { RadioButton(selected = themeMode == mode, onClick = null) },
                            onClick = {
                                viewModel.setThemeMode(mode)
                                themeMenuOpen = false
                            },
                        )
                    }
                }
            }

            if (org.transdroid.protocol.DaemonCapability.GLOBAL_SPEED_LIMITS in sessionCapabilities) {
                item { SectionHeader(stringResource(R.string.settings_speed_limits)) }
                item {
                    SpeedLimitsEditor(
                        stats = sessionStats,
                        hasAlt = org.transdroid.protocol.DaemonCapability.ALT_SPEED in sessionCapabilities,
                        onApply = { down, up -> viewModel.setGlobalSpeedLimits(down, up) },
                        onAlt = { viewModel.setAltSpeedEnabled(it) },
                    )
                }
            }

            if (profiles.size > 1) {
                item {
                    var widgetMenuOpen by remember { mutableStateOf(false) }
                    val widgetName = profiles.firstOrNull { it.id == widgetServerId }?.displayName
                        ?: stringResource(R.string.settings_widget_server_active)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_widget_server)) },
                        supportingContent = { Text(widgetName) },
                        modifier = Modifier.fillMaxWidth().clickable { widgetMenuOpen = true },
                    )
                    DropdownMenu(expanded = widgetMenuOpen, onDismissRequest = { widgetMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_widget_server_active)) },
                            onClick = {
                                viewModel.setWidgetServer(null)
                                widgetMenuOpen = false
                            },
                        )
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.displayName) },
                                onClick = {
                                    viewModel.setWidgetServer(profile.id)
                                    widgetMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_notifications)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notify_finished)) },
                    supportingContent = { Text(stringResource(R.string.settings_notify_finished_summary)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = notifyFinished,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= 33) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setNotifyFinished(context, enabled)
                                }
                            },
                        )
                    },
                )
            }

            if (rssAvailable) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_notify_rss)) },
                        trailingContent = {
                            Switch(
                                checked = notifyRss,
                                onCheckedChange = { viewModel.setNotifyRss(it) },
                            )
                        },
                    )
                }
            }

            if (updateCheckAvailable) {
                item { SectionHeader(stringResource(R.string.settings_update)) }
                item {
                    val update = availableUpdate
                    ListItem(
                        headlineContent = {
                            Text(
                                if (update != null) {
                                    stringResource(R.string.settings_update_available, update.tag)
                                } else {
                                    stringResource(R.string.settings_update_none)
                                },
                            )
                        },
                        trailingContent = if (update != null) {
                            {
                                TextButton(onClick = {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            update.htmlUrl.toUri(),
                                        ),
                                    )
                                }) { Text(stringResource(R.string.settings_update_open)) }
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            if (searchAvailable) {
                item { SectionHeader(stringResource(R.string.settings_search_providers)) }
                items(providers, key = { it.id }) { provider ->
                    ListItem(
                        headlineContent = { Text(provider.displayName) },
                        supportingContent = { Text(provider.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            editingProvider = provider
                            showProviderDialog = true
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            editingProvider = null
                            showProviderDialog = true
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_add_search_provider))
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_backup)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_export)) },
                    supportingContent = { Text(stringResource(R.string.backup_export_summary)) },
                    leadingContent = {
                        Icon(Icons.Default.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.fillMaxWidth().clickable { showExportDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_import)) },
                    supportingContent = { Text(stringResource(R.string.backup_import_summary)) },
                    leadingContent = {
                        Icon(
                            Icons.Default.SettingsBackupRestore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().clickable {
                        importPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                )
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showExportDialog) {
        PassphraseDialog(
            title = stringResource(R.string.backup_export),
            message = stringResource(R.string.backup_export_message),
            confirmLabel = stringResource(R.string.backup_export_confirm),
            onDismiss = { showExportDialog = false },
            onConfirm = { passphrase ->
                showExportDialog = false
                viewModel.createBackup(passphrase) { ok ->
                    if (ok) exportCreator.launch("transdroid-backup.tdbk")
                    else scope.launch { snackbarHostState.showSnackbar(exportFailedMessage) }
                }
            },
        )
    }

    importUri?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.backup_import),
            message = stringResource(R.string.backup_import_message),
            confirmLabel = stringResource(R.string.backup_import_confirm),
            onDismiss = { importUri = null },
            onConfirm = { passphrase ->
                importUri = null
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(64 * 1024)
                                var total = 0
                                while (true) {
                                    val read = stream.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > MAX_BACKUP_BYTES) return@use null
                                    output.write(buffer, 0, read)
                                }
                                output.toByteArray()
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bytes == null) {
                        snackbarHostState.showSnackbar(restoreInvalid)
                        return@launch
                    }
                    viewModel.restoreBackup(bytes, passphrase) { result ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                when (result) {
                                    is SettingsViewModel.RestoreResult.Success ->
                                        context.resources.getQuantityString(
                                            R.plurals.backup_restored,
                                            result.serverCount,
                                            result.serverCount,
                                        )
                                    SettingsViewModel.RestoreResult.WrongPassphrase -> restoreWrongPassphrase
                                    SettingsViewModel.RestoreResult.InvalidFile -> restoreInvalid
                                }
                            )
                        }
                    }
                }
            },
        )
    }

    if (showProviderDialog) {
        SearchProviderDialog(
            existing = editingProvider,
            onDismiss = { showProviderDialog = false },
            onSave = { provider ->
                viewModel.saveSearchProvider(provider)
                showProviderDialog = false
            },
            onDelete = editingProvider?.let { provider ->
                {
                    viewModel.deleteSearchProvider(provider.id)
                    showProviderDialog = false
                }
            },
        )
    }
}

internal fun org.transdroid.protocol.DaemonType.displayNameRes(): Int = when (this) {
    org.transdroid.protocol.DaemonType.TRANSMISSION -> R.string.client_transmission
    org.transdroid.protocol.DaemonType.QBITTORRENT -> R.string.client_qbittorrent
    org.transdroid.protocol.DaemonType.RTORRENT -> R.string.client_rtorrent
    org.transdroid.protocol.DaemonType.DELUGE -> R.string.client_deluge
}

@Composable
private fun SpeedLimitsEditor(
    stats: SessionStats?,
    hasAlt: Boolean,
    onApply: (Long?, Long?) -> Unit,
    onAlt: (Boolean) -> Unit,
) {
    var down by remember(stats?.downloadLimitBytesPerSec) {
        mutableStateOf(((stats?.downloadLimitBytesPerSec ?: 0L) / 1000).toString())
    }
    var up by remember(stats?.uploadLimitBytesPerSec) {
        mutableStateOf(((stats?.uploadLimitBytesPerSec ?: 0L) / 1000).toString())
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        stats?.freeSpaceBytes?.let {
            Text(
                stringResource(R.string.settings_free_space, formatBytes(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = down,
            onValueChange = { down = it.filter { ch -> ch.isDigit() } },
            label = { Text(stringResource(R.string.settings_speed_download)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = up,
            onValueChange = { up = it.filter { ch -> ch.isDigit() } },
            label = { Text(stringResource(R.string.settings_speed_upload)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onApply(down.toLongOrNull(), up.toLongOrNull()) }) {
            Text(stringResource(R.string.settings_apply_limits))
        }
        if (hasAlt) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = stats?.altSpeedEnabled == true, onCheckedChange = onAlt)
                Text(stringResource(R.string.settings_alt_speed), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.length >= 4) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.details_cancel)) }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchProviderDialog(
    existing: SearchProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (SearchProviderConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.settings_add_search_provider
                    else R.string.settings_edit_search_provider
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_torznab_url)) },
                    placeholder = { Text(stringResource(R.string.settings_torznab_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        SearchProviderConfig(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            url = url.trim(),
                            apiKey = apiKey.trim(),
                        )
                    )
                },
                enabled = url.trim().startsWith("http"),
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.details_remove_confirm)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.details_cancel)) }
            }
        },
    )
}
