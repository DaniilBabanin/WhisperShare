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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val _state = MutableStateFlow(
        BenchmarkUiState(
            installedModels = emptyList(),
            gpuSupported = Benchmark.gpuSupported(),
            results = emptyList(),
            running = false,
            progress = null,
            currentLabel = null,
            errorMessage = null
        )
    )
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    init {
        // Manifest read hits disk — keep it off the main thread.
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                ModelManager.listInstalled(getApplication())
            }
            _state.update { it.copy(installedModels = installed) }
        }
    }

    fun onAudioPicked(uri: Uri) {
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { ModelManager.listInstalled(ctx) }
                if (installed.isEmpty()) {
                    _state.update {
                        it.copy(errorMessage = ctx.getString(R.string.benchmark_no_models))
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        running = true,
                        progress = 0f,
                        currentLabel = ctx.getString(R.string.stage_decoding_audio),
                        errorMessage = null,
                        results = emptyList()
                    )
                }
                val pcm = AudioDecoder.decodeToPcmWithFallback(ctx, uri)
                val results = try {
                    Benchmark.run(
                        context = ctx,
                        pcm = pcm,
                        models = installed,
                        includeGpu = true,
                        threads = prefs.resolvedThreads()
                    ) { current, total, label ->
                        _state.update {
                            it.copy(
                                progress = if (total == 0) null else current.toFloat() / total,
                                currentLabel = label.ifBlank { null }
                            )
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { pcm.file.delete() }
                }
                _state.update {
                    it.copy(
                        running = false,
                        progress = null,
                        currentLabel = null,
                        results = results
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        progress = null,
                        currentLabel = null,
                        errorMessage = t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }
    }
}
