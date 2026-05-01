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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

sealed interface TranscribeUiState {
    data object Idle : TranscribeUiState
    data class Stage(val stage: String, val progress: Float?) : TranscribeUiState
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
    onRetry: (() -> Unit)?
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcription") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
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
                    Text("Starting…", style = MaterialTheme.typography.bodyMedium)
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
                }

                is TranscribeUiState.Done -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("backend: ${state.backend}") }
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text("%.1fs in %.1fs (%.1fx rt)".format(
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
                            Text("Copy")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onShare(state.text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.IosShare, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                is TranscribeUiState.Error -> {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Something went wrong",
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
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
