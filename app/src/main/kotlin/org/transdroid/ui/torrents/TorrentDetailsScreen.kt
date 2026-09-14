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
package org.transdroid.ui.torrents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.transdroid.R
import org.transdroid.protocol.TorrentFile
import org.transdroid.ui.message
import org.transdroid.protocol.DaemonCapability
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentStatus
import org.transdroid.ui.label
import org.transdroid.ui.statusLabel
import org.transdroid.ui.theme.accentColor
import org.transdroid.util.formatBytes
import org.transdroid.util.formatEta
import org.transdroid.util.formatRatio
import org.transdroid.util.formatSpeed

/** Full-screen torrent details for compact widths; two-pane layouts embed the content directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailsScreen(
    viewModel: TorrentsViewModel,
    torrentId: String,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val torrent = ui.torrents.firstOrNull { it.id == torrentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        torrent?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (torrent == null) {
                Text(
                    stringResource(R.string.details_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                TorrentDetailsContent(viewModel = viewModel, torrent = torrent, onRemoved = onBack)
            }
        }
    }
}

@Composable
fun TorrentDetailsContent(
    viewModel: TorrentsViewModel,
    torrent: Torrent,
    onRemoved: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showLabelDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(torrent.id) { viewModel.loadFiles(torrent.id) }
    val files = ui.files[torrent.id]

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
        Text(torrent.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { torrent.displayProgress },
            color = torrent.status.accentColor,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                torrent.statusLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = torrent.status.accentColor,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(torrent.displayProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        torrent.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val paused = torrent.status == TorrentStatus.PAUSED
            Button(onClick = { viewModel.toggleStartPause(torrent) }) {
                val label = stringResource(if (paused) R.string.details_start else R.string.details_pause)
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = label,
                )
                Text(label)
            }
            OutlinedButton(onClick = { showRemoveDialog = true }) {
                val label = stringResource(R.string.details_remove)
                Icon(Icons.Default.Delete, contentDescription = label)
                Text(label)
            }
        }
        if (ui.capabilities.any {
                it in setOf(
                    DaemonCapability.RECHECK,
                    DaemonCapability.REANNOUNCE,
                    DaemonCapability.SET_LABELS,
                    DaemonCapability.SET_LOCATION,
                )
            }
        ) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (DaemonCapability.RECHECK in ui.capabilities) {
                    TextButton(onClick = { viewModel.recheck(torrent.id) }) {
                        Text(stringResource(R.string.details_recheck))
                    }
                }
                if (DaemonCapability.REANNOUNCE in ui.capabilities) {
                    TextButton(onClick = { viewModel.reannounce(torrent.id) }) {
                        Text(stringResource(R.string.details_reannounce))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (DaemonCapability.SET_LABELS in ui.capabilities) {
                    TextButton(onClick = { showLabelDialog = true }) {
                        Text(stringResource(R.string.details_set_label))
                    }
                }
                if (DaemonCapability.SET_LOCATION in ui.capabilities) {
                    TextButton(onClick = { showLocationDialog = true }) {
                        Text(stringResource(R.string.details_set_location))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.details_section_transfer),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        DetailRow(stringResource(R.string.details_size), formatBytes(torrent.sizeBytes))
        DetailRow(
            stringResource(R.string.details_downloaded),
            if (torrent.downloadRate > 0) {
                stringResource(
                    R.string.details_transferred_with_speed,
                    formatBytes(torrent.downloadedBytes),
                    formatSpeed(torrent.downloadRate),
                )
            } else {
                formatBytes(torrent.downloadedBytes)
            },
        )
        DetailRow(
            stringResource(R.string.details_uploaded),
            if (torrent.uploadRate > 0) {
                stringResource(
                    R.string.details_uploaded_with_speed,
                    formatBytes(torrent.uploadedBytes),
                    formatSpeed(torrent.uploadRate),
                )
            } else {
                formatBytes(torrent.uploadedBytes)
            },
        )
        DetailRow(stringResource(R.string.details_ratio), formatRatio(torrent.ratio))
        DetailRow(stringResource(R.string.details_peers), torrent.peersConnected.toString())
        if (torrent.labels.isNotEmpty()) {
            DetailRow(stringResource(R.string.details_labels), torrent.labels.joinToString())
        }
        formatEta(torrent.etaSeconds)?.let { DetailRow(stringResource(R.string.details_eta), it) }
        torrent.addedTimestamp?.let {
            DetailRow(
                stringResource(R.string.details_added),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)),
            )
        }
        torrent.downloadDir?.let { DetailRow(stringResource(R.string.details_location), it) }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.details_section_files),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        }
        when {
            ui.filesError != null && files == null -> {
                Text(
                    ui.filesError!!.message(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            files == null -> {
                Text(
                    stringResource(R.string.details_files_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            files.isEmpty() -> {
                Text(
                    stringResource(R.string.details_files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                ) {
                    items(files, key = { it.index }) { file ->
                        FileRow(torrentId = torrent.id, file = file, viewModel = viewModel)
                    }
                }
            }
        }
    }

    if (showRemoveDialog) {
        var alsoDeleteData by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.details_remove_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.details_remove_message, torrent.name))
                    if (DaemonCapability.DELETE_DATA in ui.capabilities) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { alsoDeleteData = !alsoDeleteData },
                        ) {
                            Checkbox(checked = alsoDeleteData, onCheckedChange = { alsoDeleteData = it })
                            Text(stringResource(R.string.details_remove_also_data))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    viewModel.remove(torrent, alsoDeleteData)
                    onRemoved?.invoke()
                }) {
                    Text(stringResource(R.string.details_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.details_cancel))
                }
            },
        )
    }

    if (showLabelDialog) {
        var label by remember { mutableStateOf(torrent.labels.firstOrNull().orEmpty()) }
        AlertDialog(
            onDismissRequest = { showLabelDialog = false },
            title = { Text(stringResource(R.string.details_set_label)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.details_labels)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setLabels(torrent.id, listOf(label.trim()).filter { it.isNotEmpty() })
                    showLabelDialog = false
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showLabelDialog = false }) {
                    Text(stringResource(R.string.details_cancel))
                }
            },
        )
    }

    if (showLocationDialog) {
        var path by remember { mutableStateOf(torrent.downloadDir.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text(stringResource(R.string.details_set_location)) },
            text = {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.details_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setLocation(torrent.id, path.trim(), moveData = true)
                        showLocationDialog = false
                    },
                    enabled = path.isNotBlank(),
                ) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDialog = false }) {
                    Text(stringResource(R.string.details_cancel))
                }
            },
        )
    }
}

@Composable
private fun FileRow(torrentId: String, file: TorrentFile, viewModel: TorrentsViewModel) {
    var priorityMenuOpen by remember(file.index) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { priorityMenuOpen = true }
            .padding(vertical = 6.dp),
    ) {
        Text(
            file.path,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { file.progress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Text(
            stringResource(
                R.string.details_file_meta,
                formatBytes(file.downloadedBytes),
                formatBytes(file.sizeBytes),
                file.priority.label(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(
            expanded = priorityMenuOpen,
            onDismissRequest = { priorityMenuOpen = false },
        ) {
            FilePriority.entries.forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priority.label()) },
                    leadingIcon = { RadioButton(selected = priority == file.priority, onClick = null) },
                    onClick = {
                        priorityMenuOpen = false
                        if (priority != file.priority) {
                            viewModel.setFilePriority(torrentId, file, priority)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FilePriority.label(): String = stringResource(
    when (this) {
        FilePriority.OFF -> R.string.priority_off
        FilePriority.LOW -> R.string.priority_low
        FilePriority.NORMAL -> R.string.priority_normal
        FilePriority.HIGH -> R.string.priority_high
    }
)

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.35f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
