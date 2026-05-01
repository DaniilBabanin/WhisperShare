package io.whispershare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.whispershare.ModelManager

data class HomeUiState(
    val installed: Set<String>,
    val selected: String,
    val downloading: Map<String, Int>,
    val useGpu: Boolean,
    val language: String,
    val translateToEnglish: Boolean,
    val errorMessage: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelectModel: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleGpu: (Boolean) -> Unit,
    onSetLanguage: (String) -> Unit,
    onToggleTranslate: (Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("WhisperShare") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Share any voice message to WhisperShare to transcribe it locally on this device. " +
                "All processing happens on your phone — no audio leaves it.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))
            SectionHeader("Models")

            ModelManager.Model.entries.forEach { model ->
                ModelRow(
                    name = model.name,
                    title = model.displayName,
                    subtitle = "${model.approxSizeMb} MB · " +
                        if (model.multilingual) "100+ languages" else "English only",
                    selected = state.selected == model.name,
                    installed = state.installed.contains(model.name),
                    progress = state.downloading[model.name],
                    onSelect = { onSelectModel(model.name) },
                    onDownload = { onDownload(model.name) },
                    onDelete = { onDelete(model.name) }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Performance")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use GPU when available", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Vulkan acceleration on Pixel 9 (Mali-G715). Falls back to CPU automatically " +
                        "if the build doesn't include Vulkan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.useGpu, onCheckedChange = onToggleGpu)
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Language")

            var langInput by remember(state.language) { mutableStateOf(state.language) }
            OutlinedTextField(
                value = langInput,
                onValueChange = {
                    langInput = it.lowercase().take(5)
                    onSetLanguage(langInput)
                },
                label = { Text("Language (auto if empty)") },
                placeholder = { Text("e.g. en, de, fr") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Translate to English", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Whisper will translate non-English audio to English instead of transcribing in source language.",
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
                "Tip: long-press a voice message in WhatsApp / Telegram / Signal and choose Share → WhisperShare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
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
    name: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    installed: Boolean,
    progress: Int?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
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
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("$progress%", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (installed) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                }
            } else if (progress == null) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "Download")
                }
            }
        }
    }
}
