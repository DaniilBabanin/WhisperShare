package io.whispershare.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.whispershare.ModelManager
import io.whispershare.R
import kotlinx.coroutines.delay

data class HomeUiState(
    val entries: List<ModelManager.ModelEntry>,
    val installed: Set<String>,
    val downloading: Map<String, ModelManager.DownloadProgress>,
    val selectedId: String,
    val useGpu: Boolean,
    val language: String,
    val translateToEnglish: Boolean,
    val threads: Int,
    val highQuality: Boolean,
    val developerMode: Boolean = false,
    val skipModelVerification: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelectModel: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImportModel: (Uri, String, Boolean) -> Unit,
    onToggleGpu: (Boolean) -> Unit,
    onSetLanguage: (String) -> Unit,
    onToggleTranslate: (Boolean) -> Unit,
    onSetThreads: (Int) -> Unit,
    onToggleHighQuality: (Boolean) -> Unit,
    onSetDeveloperMode: (Boolean) -> Unit,
    onToggleSkipModelVerification: (Boolean) -> Unit,
    onOpenBenchmark: () -> Unit,
    onTranscribeAudio: (Uri) -> Unit
) {
    val maxThreads = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(2) }
    val context = LocalContext.current
    val tapsRequired = 5
    var tapCount by remember { mutableIntStateOf(0) }

    // Reset the tap counter if the user pauses between taps.
    LaunchedEffect(tapCount) {
        if (tapCount in 1 until tapsRequired) {
            delay(1500)
            tapCount = 0
        }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onTranscribeAudio(uri)
    }

    if (pendingUri != null) {
        ImportModelDialog(
            onDismiss = { pendingUri = null },
            onConfirm = { name, multi ->
                pendingUri?.let { onImportModel(it, name, multi) }
                pendingUri = null
            }
        )
    }

    var pendingDelete by remember { mutableStateOf<ModelManager.ModelEntry?>(null) }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_message, entry.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry.id)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.delete_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.home_intro),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*", "application/ogg")) },
                enabled = state.installed.contains(state.selectedId),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.AudioFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.transcribe_file_button))
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.section_models))

            state.entries.forEach { entry ->
                ModelRow(
                    id = entry.id,
                    title = entry.displayName,
                    subtitle = buildSubtitle(entry),
                    selected = state.selectedId == entry.id,
                    installed = state.installed.contains(entry.id),
                    progress = state.downloading[entry.id],
                    isCustom = entry is ModelManager.CustomModel,
                    isDownloadable = entry is ModelManager.BuiltInModel,
                    onSelect = { onSelectModel(entry.id) },
                    onDownload = { onDownload(entry.id) },
                    onCancelDownload = { onCancelDownload(entry.id) },
                    onDelete = { pendingDelete = entry }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.section_performance))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gpu_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.gpu_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.useGpu, onCheckedChange = onToggleGpu)
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.high_quality_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.high_quality_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.highQuality, onCheckedChange = onToggleHighQuality)
            }

            if (state.developerMode) {
                Spacer(Modifier.height(12.dp))

                Column {
                    val label = if (state.threads <= 0)
                        stringResource(R.string.threads_auto)
                    else
                        stringResource(R.string.threads_count, state.threads)
                    Text(
                        stringResource(R.string.threads_title, label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.threads_subtitle, maxThreads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = state.threads.toFloat(),
                        onValueChange = { onSetThreads(it.toInt()) },
                        valueRange = 0f..maxThreads.toFloat(),
                        steps = (maxThreads - 1).coerceAtLeast(0)
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenBenchmark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.benchmark_button))
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_model_button))
                }
                Text(
                    stringResource(R.string.import_model_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.skip_verify_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.skip_verify_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.skipModelVerification,
                        onCheckedChange = onToggleSkipModelVerification
                    )
                }

                TextButton(
                    onClick = { onSetDeveloperMode(false) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.dev_hide_button))
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.section_language))

            var langInput by remember(state.language) { mutableStateOf(state.language) }
            OutlinedTextField(
                value = langInput,
                onValueChange = {
                    langInput = it.lowercase().take(5)
                    onSetLanguage(langInput)
                },
                label = { Text(stringResource(R.string.lang_label)) },
                placeholder = { Text(stringResource(R.string.lang_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.translate_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.translate_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.translateToEnglish, onCheckedChange = onToggleTranslate)
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(R.string.share_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = !state.developerMode) {
                    val next = tapCount + 1
                    val remaining = tapsRequired - next
                    when {
                        next >= tapsRequired -> {
                            tapCount = 0
                            onSetDeveloperMode(true)
                            Toast.makeText(
                                context,
                                context.getString(R.string.dev_unlocked),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        remaining in 1..3 -> {
                            tapCount = next
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.dev_unlock_progress, remaining, remaining
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        else -> tapCount = next
                    }
                }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun buildSubtitle(entry: ModelManager.ModelEntry): String {
    val size = if (entry.approxSizeMb > 0) stringResource(R.string.model_size_mb, entry.approxSizeMb)
        else stringResource(R.string.model_size_unknown)
    val lang = if (entry.multilingual) stringResource(R.string.model_langs_multilingual)
        else stringResource(R.string.model_langs_english_only)
    val origin = if (entry is ModelManager.CustomModel) " · ${stringResource(R.string.model_origin_imported)}" else ""
    return "$size · $lang$origin"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ModelRow(
    id: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    installed: Boolean,
    progress: ModelManager.DownloadProgress?,
    isCustom: Boolean,
    isDownloadable: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        tonalElevation = if (selected) 4.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            RadioButton(
                selected = selected && installed,
                onClick = { if (installed) onSelect() }
            )
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (progress) {
                    is ModelManager.DownloadProgress.Percent -> {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${progress.percent}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelManager.DownloadProgress.DownloadedMb -> {
                        Spacer(Modifier.height(6.dp))
                        // Total size unknown (chunked response) — indeterminate bar + MB counter.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.download_progress_mb, progress.mb),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    null -> {}
                }
            }
            if (installed || isCustom) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_cd))
                }
            } else if (progress != null) {
                IconButton(onClick = onCancelDownload) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel_download_cd))
                }
            } else if (isDownloadable) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = stringResource(R.string.download_cd))
                }
            }
        }
    }
}

@Composable
private fun ImportModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var multilingual by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_dialog_name)) },
                    placeholder = { Text(stringResource(R.string.import_dialog_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.import_dialog_multilingual))
                        Text(
                            stringResource(R.string.import_dialog_multilingual_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = multilingual, onCheckedChange = { multilingual = it })
                }
            }
        },
        confirmButton = {
            val defaultName = stringResource(R.string.import_default_name)
            TextButton(
                onClick = {
                    val final = name.ifBlank { defaultName }
                    onConfirm(final, multilingual)
                }
            ) { Text(stringResource(R.string.import_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
