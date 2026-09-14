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
package org.transdroid.ui.add

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.transdroid.R
import org.transdroid.data.ServerProfile
import org.transdroid.ui.message
import org.transdroid.ui.torrents.TorrentsViewModel
import org.transdroid.ui.torrents.UiError

/** .torrent files are tiny; anything larger than this is not one. */
private const val MAX_TORRENT_FILE_BYTES = 10L * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTorrentScreen(
    viewModel: TorrentsViewModel,
    initialUrl: String,
    onDone: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialIsFile = initialUrl.startsWith("content:") || initialUrl.startsWith("file:")
    var url by rememberSaveable { mutableStateOf(if (initialIsFile) "" else initialUrl) }
    var fileUri by rememberSaveable { mutableStateOf(if (initialIsFile) initialUrl else null) }
    var invalidInput by rememberSaveable { mutableStateOf(false) }
    var startPaused by rememberSaveable { mutableStateOf(false) }
    var downloadDir by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var targetServerId by rememberSaveable { mutableStateOf(ui.activeProfile?.id) }
    // Deliberately not saveable: the completion callback writes to this composition's state,
    // so restoring `true` across recreation would leave the button disabled forever
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiError?>(null) }
    var fileReadFailed by remember { mutableStateOf(false) }

    // Saveable so activity recreation cannot re-fire the single-server auto-submit
    var autoSubmitted by rememberSaveable { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileUri = uri.toString()
            invalidInput = false
            autoSubmitted = false
        }
    }

    fun currentOptions() = org.transdroid.protocol.AddOptions(
        startPaused = startPaused,
        downloadDir = downloadDir.trim().takeIf { it.isNotEmpty() },
        labels = listOf(label.trim()).filter { it.isNotEmpty() },
    )

    fun submit() {
        val target = ui.profiles.firstOrNull { it.id == targetServerId } ?: ui.activeProfile
        if (target != null && target.id != ui.activeProfile?.id) {
            viewModel.setActiveServer(target.id)
        }
        val pickedFile = fileUri
        error = null
        fileReadFailed = false
        if (pickedFile != null) {
            submitting = true
            scope.launch {
                val contents = withContext(Dispatchers.IO) { readTorrentFile(context, Uri.parse(pickedFile)) }
                if (contents == null) {
                    submitting = false
                    fileReadFailed = true
                } else {
                    viewModel.addFile(contents.first, contents.second, currentOptions()) { result ->
                        submitting = false
                        if (result == null) onDone() else error = result
                    }
                }
            }
            return
        }
        val trimmed = url.trim()
        val valid = trimmed.startsWith("magnet:") ||
            trimmed.startsWith("http://") || trimmed.startsWith("https://")
        if (!valid) {
            invalidInput = true
        } else {
            submitting = true
            viewModel.add(trimmed, currentOptions()) { result ->
                submitting = false
                if (result == null) onDone() else error = result
            }
        }
    }

    // With a single configured server there is nothing to choose: a picked or opened
    // .torrent file is added right away instead of asking for another confirming tap.
    // (The paused checkbox sits above the picker, so that choice still comes first.)
    LaunchedEffect(fileUri, url, ui.profileCount) {
        val incoming = fileUri != null ||
            url.startsWith("magnet:") || url.startsWith("http://") || url.startsWith("https://")
        if (incoming && ui.profileCount == 1 && !autoSubmitted &&
            !submitting && error == null && !fileReadFailed && initialUrl.isNotBlank()
        ) {
            autoSubmitted = true
            submit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.details_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
        ) {
            val pickedFile = fileUri
            if (pickedFile == null) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        invalidInput = false
                    },
                    label = { Text(stringResource(R.string.add_url_label)) },
                    placeholder = { Text(stringResource(R.string.add_url_hint)) },
                    isError = invalidInput,
                    supportingText = if (invalidInput) {
                        { Text(stringResource(R.string.add_invalid)) }
                    } else {
                        null
                    },
                    minLines = 3,
                    // Magnet links with many trackers are enormous; cap the field so the
                    // add button can never be pushed off-screen (text scrolls inside)
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                AddPausedCheckbox(startPaused) { startPaused = it }
                AddExtraFields(
                    profiles = ui.profiles,
                    selectedId = targetServerId ?: ui.activeProfile?.id,
                    onSelectServer = { targetServerId = it },
                    downloadDir = downloadDir,
                    onDownloadDir = { downloadDir = it },
                    label = label,
                    onLabel = { label = it },
                    showDirAndLabel = org.transdroid.protocol.DaemonCapability.ADD_OPTIONS in ui.capabilities,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_pick_file))
                }
            } else {
                AssistChip(
                    onClick = {},
                    label = { Text(displayName(context, Uri.parse(pickedFile))) },
                    trailingIcon = {
                        IconButton(onClick = { fileUri = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.details_cancel))
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                AddPausedCheckbox(startPaused) { startPaused = it }
                AddExtraFields(
                    profiles = ui.profiles,
                    selectedId = targetServerId ?: ui.activeProfile?.id,
                    onSelectServer = { targetServerId = it },
                    downloadDir = downloadDir,
                    onDownloadDir = { downloadDir = it },
                    label = label,
                    onLabel = { label = it },
                    showDirAndLabel = org.transdroid.protocol.DaemonCapability.ADD_OPTIONS in ui.capabilities,
                )
            }
            if (fileReadFailed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.add_file_read_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { submit() },
                enabled = !submitting && ui.activeProfile != null && (url.isNotBlank() || fileUri != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_button, ui.activeProfile?.displayName ?: ""))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExtraFields(
    profiles: List<ServerProfile>,
    selectedId: String?,
    onSelectServer: (String) -> Unit,
    downloadDir: String,
    onDownloadDir: (String) -> Unit,
    label: String,
    onLabel: (String) -> Unit,
    showDirAndLabel: Boolean,
) {
    if (profiles.size > 1) {
        var expanded by remember { mutableStateOf(false) }
        val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.add_server)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.displayName) },
                        onClick = {
                            onSelectServer(profile.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    if (showDirAndLabel) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = downloadDir,
            onValueChange = onDownloadDir,
            label = { Text(stringResource(R.string.add_download_dir)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = onLabel,
            label = { Text(stringResource(R.string.add_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddPausedCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column {
            Text(stringResource(R.string.add_paused), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.add_paused_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Reads the picked .torrent file, returning (fileName, contents) or null on failure. Reads
 * at most [MAX_TORRENT_FILE_BYTES] so a mistakenly picked huge file cannot exhaust memory.
 */
private fun readTorrentFile(context: Context, uri: Uri): Pair<String, ByteArray>? = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_TORRENT_FILE_BYTES) return null
            output.write(buffer, 0, read)
        }
        val contents = output.toByteArray()
        if (contents.isEmpty()) null else displayName(context, uri) to contents
    }
} catch (e: Exception) {
    null
}

private fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
    } catch (e: Exception) {
        // Fall through to the path-based name
    }
    return uri.lastPathSegment ?: "file.torrent"
}
