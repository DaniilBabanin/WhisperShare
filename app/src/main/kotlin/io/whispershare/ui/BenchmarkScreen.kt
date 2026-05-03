package io.whispershare.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.whispershare.BenchmarkResult
import io.whispershare.ModelManager
import io.whispershare.R

data class BenchmarkUiState(
    val installedModels: List<ModelManager.ModelEntry>,
    val gpuSupported: Boolean,
    val results: List<BenchmarkResult>,
    val running: Boolean,
    val progress: Float?,
    val currentLabel: String?,
    val errorMessage: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    state: BenchmarkUiState,
    onClose: () -> Unit,
    onPickAudio: (Uri) -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickAudio(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.benchmark_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
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
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.benchmark_intro),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            if (state.installedModels.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.benchmark_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            Text(
                stringResource(
                    R.string.benchmark_summary,
                    state.installedModels.size,
                    if (state.gpuSupported) stringResource(R.string.benchmark_summary_gpu)
                    else stringResource(R.string.benchmark_summary_cpu_only)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { picker.launch(arrayOf("audio/*", "*/*")) },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.benchmark_pick_audio))
            }

            if (state.running) {
                Spacer(Modifier.height(16.dp))
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
                    state.currentLabel ?: stringResource(R.string.benchmark_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (state.results.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.benchmark_results_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                state.results.forEach { r ->
                    BenchmarkRow(r)
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BenchmarkRow(r: BenchmarkResult) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.modelName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (r.error != null) "error: ${r.error}"
                    else "%.1fs in %.1fs".format(r.durationSec, r.elapsedMs / 1000.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(r.backend) }
                )
                if (r.error == null) {
                    Text(
                        "%.1fx rt".format(r.realtimeFactor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
