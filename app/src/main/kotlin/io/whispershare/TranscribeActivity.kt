package io.whispershare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.whispershare.ui.TranscribeScreen
import io.whispershare.ui.TranscribeUiState
import io.whispershare.ui.theme.WhisperShareTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TranscribeActivity : ComponentActivity() {

    private val vm: TranscribeViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = extractUri(intent)
        if (uri == null) {
            setContent {
                WhisperShareTheme {
                    TranscribeScreen(
                        state = TranscribeUiState.Error("No audio file received."),
                        onClose = { finish() },
                        onCopy = { _ -> },
                        onShare = { _ -> },
                        onRetry = null
                    )
                }
            }
            return
        }

        vm.start(uri)

        setContent {
            WhisperShareTheme {
                val state by vm.state.collectAsState()
                TranscribeScreen(
                    state = state,
                    onClose = { finish() },
                    onCopy = { text -> ShareUtils.copy(this, text) },
                    onShare = { text -> ShareUtils.share(this, text) },
                    onRetry = { vm.start(uri) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { vm.start(it) }
    }

    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            Intent.ACTION_VIEW -> intent.data
            else -> intent.data ?: intent.parcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(name) as? T

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)
}

class TranscribeViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val _state = MutableStateFlow<TranscribeUiState>(TranscribeUiState.Idle)
    val state: StateFlow<TranscribeUiState> = _state.asStateFlow()

    fun start(uri: Uri) {
        viewModelScope.launch {
            try {
                _state.value = TranscribeUiState.Stage(stage = "Loading model…", progress = null)

                val ctx = getApplication<android.app.Application>()
                val entry = ModelManager.entryById(ctx, prefs.selectedModelId)
                if (entry == null) {
                    _state.value = TranscribeUiState.Error(
                        "Selected model is no longer available. Open WhisperShare to pick another."
                    )
                    return@launch
                }
                val modelFile = ModelManager.fileFor(ctx, entry)
                if (!modelFile.exists()) {
                    _state.value = TranscribeUiState.Error(
                        "Model '${entry.displayName}' is not downloaded. Open WhisperShare to download it first."
                    )
                    return@launch
                }

                WhisperEngine.load(modelFile, useGpu = prefs.useGpu)
                    .getOrElse {
                        _state.value = TranscribeUiState.Error("Failed to load model: ${it.message}")
                        return@launch
                    }

                _state.value = TranscribeUiState.Stage("Decoding audio…", null)
                val pcm = AudioDecoder.decodeToPcmWithFallback(ctx, uri)
                val durationSec = pcm.size / 16_000.0

                _state.value = TranscribeUiState.Stage(
                    "Transcribing %.1fs of audio…".format(durationSec),
                    0f
                )

                val builder = StringBuilder()
                val started = System.currentTimeMillis()
                val text = WhisperEngine.transcribe(
                    pcm16k = pcm,
                    language = prefs.language.takeIf { it.isNotBlank() },
                    translate = prefs.translateToEnglish,
                    threads = prefs.resolvedThreads(),
                    highQuality = prefs.highQuality,
                    onSegment = { seg ->
                        builder.append(seg)
                        _state.value = TranscribeUiState.Streaming(
                            partial = builder.toString(),
                            durationSec = durationSec,
                            progress = (_state.value as? TranscribeUiState.Streaming)?.progress
                        )
                    },
                    onProgress = { pct ->
                        val cur = _state.value
                        val partial = (cur as? TranscribeUiState.Streaming)?.partial ?: ""
                        _state.value = TranscribeUiState.Streaming(
                            partial = partial,
                            durationSec = durationSec,
                            progress = pct.coerceIn(0f, 1f)
                        )
                    }
                )
                val ms = System.currentTimeMillis() - started

                _state.value = TranscribeUiState.Done(
                    text = text.ifBlank { "(no speech detected)" },
                    durationSec = durationSec,
                    elapsedMs = ms,
                    backend = WhisperEngine.activeBackend
                )
            } catch (t: Throwable) {
                _state.value = TranscribeUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
