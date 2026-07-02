package io.whispershare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import io.whispershare.R

sealed interface TranscribeUiState {
    data object Idle : TranscribeUiState
    data class Stage(val stage: String, val progress: Float?) : TranscribeUiState
    /** In-flight transcription with partial text + optional progress (0..1). */
    data class Streaming(val partial: String, val durationSec: Double, val progress: Float?) : TranscribeUiState
    data class Done(val text: String, val durationSec: Double, val elapsedMs: Long, val backend: String) : TranscribeUiState
    data class Error(val message: String) : TranscribeUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(
    state: TranscribeUiState,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transcribe_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close_cd))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            when (state) {
                is TranscribeUiState.Idle -> {
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.transcribe_starting), style = MaterialTheme.typography.bodyMedium)
                }

                is TranscribeUiState.Stage -> {
                    Spacer(Modifier.height(24.dp))
                    if (state.progress != null) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(state.stage, style = MaterialTheme.typography.bodyMedium)
                    if (onCancel != null) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onCancel) {
                            Text(stringResource(R.string.cancel_transcription))
                        }
                    }
                }

                is TranscribeUiState.Streaming -> {
                    Spacer(Modifier.height(8.dp))
                    if (state.progress != null) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.progress != null)
                            stringResource(R.string.transcribe_progress_percent, state.progress * 100, state.durationSec)
                        else
                            stringResource(R.string.transcribe_progress_duration, state.durationSec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                state.partial.ifBlank { "…" },
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (onCancel != null) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.cancel_transcription))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                is TranscribeUiState.Done -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(stringResource(R.string.backend_chip, state.backend)) }
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(stringResource(
                                    R.string.timing_chip,
                                    state.durationSec,
                                    state.elapsedMs / 1000.0,
                                    state.durationSec / (state.elapsedMs / 1000.0).coerceAtLeast(0.001)
                                ))
                            }
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                state.text,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { onCopy(state.text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.copy_button))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onShare(state.text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.IosShare, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.share_button))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                is TranscribeUiState.Error -> {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.error_generic_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    if (onRetry != null) {
                        OutlinedButton(onClick = onRetry) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                }
            }
        }
    }
}
