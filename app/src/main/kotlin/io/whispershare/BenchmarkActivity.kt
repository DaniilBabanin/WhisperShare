package io.whispershare

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.whispershare.ui.BenchmarkScreen
import io.whispershare.ui.BenchmarkUiState
import io.whispershare.ui.theme.WhisperShareTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BenchmarkActivity : ComponentActivity() {

    private val vm: BenchmarkViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhisperShareTheme {
                val state by vm.state.collectAsState()
                BenchmarkScreen(
                    state = state,
                    onClose = { finish() },
                    onPickAudio = vm::onAudioPicked
                )
            }
        }
    }
}

class BenchmarkViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private fun initial(): BenchmarkUiState {
        val ctx = getApplication<android.app.Application>()
        return BenchmarkUiState(
            installedModels = ModelManager.listInstalled(ctx),
            gpuSupported = Benchmark.gpuSupported(),
            results = emptyList(),
            running = false,
            progress = null,
            currentLabel = null,
            errorMessage = null
        )
    }

    fun onAudioPicked(uri: Uri) {
        val ctx = getApplication<android.app.Application>()
        val installed = ModelManager.listInstalled(ctx)
        if (installed.isEmpty()) {
            _state.value = _state.value.copy(
                errorMessage = "No models installed. Download or import a model first."
            )
            return
        }
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    running = true,
                    progress = 0f,
                    currentLabel = "Decoding audio…",
                    errorMessage = null,
                    results = emptyList()
                )
                val pcm = AudioDecoder.decodeToPcmWithFallback(ctx, uri)
                val results = Benchmark.run(
                    context = ctx,
                    pcm = pcm,
                    models = installed,
                    includeGpu = true,
                    threads = prefs.resolvedThreads()
                ) { current, total, label ->
                    _state.value = _state.value.copy(
                        progress = if (total == 0) null else current.toFloat() / total,
                        currentLabel = label.ifBlank { null }
                    )
                }
                _state.value = _state.value.copy(
                    running = false,
                    progress = null,
                    currentLabel = null,
                    results = results
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    progress = null,
                    currentLabel = null,
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
            }
        }
    }
}
