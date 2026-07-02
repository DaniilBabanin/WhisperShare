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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                        state = TranscribeUiState.Error(getString(R.string.error_no_audio)),
                        onClose = { finish() },
                        onCopy = { _ -> },
                        onShare = { _ -> },
                        onCancel = null,
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
                    onCancel = { vm.cancel() },
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

    private var job: Job? = null

    private fun str(resId: Int, vararg args: Any): String =
        getApplication<android.app.Application>().getString(resId, *args)

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = TranscribeUiState.Error(str(R.string.transcription_cancelled))
    }

    fun start(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            // Native callbacks keep firing until the abort flag is polled;
            // guard them with this so a stale segment can't overwrite the
            // "cancelled" state written by cancel().
            val self = coroutineContext.job
            var crumb: File? = null
            try {
                _state.value = TranscribeUiState.Stage(stage = str(R.string.stage_loading_model), progress = null)

                val ctx = getApplication<android.app.Application>()
                val entry = withContext(Dispatchers.IO) {
                    ModelManager.entryById(ctx, prefs.selectedModelId)
                }
                if (entry == null) {
                    _state.value = TranscribeUiState.Error(str(R.string.error_model_unavailable))
                    return@launch
                }
                val modelFile = ModelManager.fileFor(ctx, entry)
                if (!withContext(Dispatchers.IO) { modelFile.exists() }) {
                    _state.value = TranscribeUiState.Error(
                        str(R.string.error_model_not_downloaded, entry.displayName)
                    )
                    return@launch
                }

                var useGpu = prefs.useGpu
                var notice: String? = null

                // Pick up a one-shot notice from a previous-run GPU crash.
                if (prefs.gpuCrashedNotice) {
                    prefs.gpuCrashedNotice = false
                    notice = str(R.string.notice_gpu_crash_previous)
                }

                val loadResult = WhisperEngine.load(modelFile, useGpu = useGpu)
                if (loadResult.isFailure && useGpu) {
                    // GPU init blew up (e.g. Mali driver) — fall back to CPU and remember it.
                    prefs.useGpu = false
                    useGpu = false
                    notice = str(R.string.notice_gpu_init_failed)
                    WhisperEngine.load(modelFile, useGpu = false)
                        .getOrElse {
                            _state.value = TranscribeUiState.Error(str(R.string.error_load_model, it.message ?: ""))
                            return@launch
                        }
                } else if (loadResult.isFailure) {
                    _state.value = TranscribeUiState.Error(
                        str(R.string.error_load_model, loadResult.exceptionOrNull()?.message ?: "")
                    )
                    return@launch
                }

                _state.value = TranscribeUiState.Stage(str(R.string.stage_decoding_audio), null)
                val pcm = AudioDecoder.decodeToPcmWithFallback(ctx, uri)
                val durationSec = pcm.size / 16_000.0

                suspend fun runOnce(): String {
                    _state.value = TranscribeUiState.Stage(
                        str(R.string.transcribe_progress_duration, durationSec),
                        0f
                    )
                    val builder = StringBuilder()
                    return WhisperEngine.transcribe(
                        pcm16k = pcm,
                        language = prefs.language.takeIf { it.isNotBlank() },
                        translate = prefs.translateToEnglish,
                        threads = prefs.resolvedThreads(),
                        highQuality = prefs.highQuality,
                        onSegment = { seg ->
                            builder.append(seg)
                            val partial = builder.toString()
                            if (self.isActive) {
                                _state.update { cur ->
                                    TranscribeUiState.Streaming(
                                        partial = partial,
                                        durationSec = durationSec,
                                        progress = (cur as? TranscribeUiState.Streaming)?.progress
                                    )
                                }
                            }
                        },
                        onProgress = { pct ->
                            if (self.isActive) {
                                _state.update { cur ->
                                    TranscribeUiState.Streaming(
                                        partial = (cur as? TranscribeUiState.Streaming)?.partial ?: "",
                                        durationSec = durationSec,
                                        progress = pct.coerceIn(0f, 1f)
                                    )
                                }
                            }
                        }
                    )
                }

                // Crash-crumb: write before any GPU run. Its only job is to
                // detect hard native aborts (ggml_abort, vk::DeviceLostError)
                // that kill the process before Kotlin can react — then the crumb
                // survives and WhisperApp.onCreate flips us to CPU on next
                // launch. Every path that stays in Kotlin (success, exception,
                // cancellation) must delete it — see the finally below.
                if (useGpu) {
                    val c = File(ctx.filesDir, WhisperApp.GPU_CRUMB_FILE)
                    withContext(Dispatchers.IO) { c.writeText("running") }
                    crumb = c
                }

                // Snapshot the native error so a stale message from a previous
                // failed load/run can't trigger a false GPU-crash retry below.
                val errorBefore = WhisperEngine.lastError()
                val started = System.currentTimeMillis()
                var text = runOnce()
                crumb?.let { c -> withContext(Dispatchers.IO) { c.delete() } }
                crumb = null
                val errorNow = WhisperEngine.lastError()
                if (text.isBlank() && useGpu && errorNow.isNotBlank() && errorNow != errorBefore) {
                    // Native error mid-transcribe — most likely vk::DeviceLostError. Force CPU and retry.
                    prefs.useGpu = false
                    useGpu = false
                    notice = str(R.string.notice_gpu_error_switched, errorNow)
                    WhisperEngine.release()
                    WhisperEngine.load(modelFile, useGpu = false).getOrElse {
                        _state.value = TranscribeUiState.Error(str(R.string.error_reload_cpu, it.message ?: ""))
                        return@launch
                    }
                    text = runOnce()
                }
                val ms = System.currentTimeMillis() - started

                _state.value = TranscribeUiState.Done(
                    text = listOfNotNull(notice, text.ifBlank { str(R.string.no_speech_detected) })
                        .joinToString("\n\n"),
                    durationSec = durationSec,
                    elapsedMs = ms,
                    backend = WhisperEngine.activeBackend
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.value = TranscribeUiState.Error(t.message ?: t.javaClass.simpleName)
            } finally {
                // The crumb must only survive a hard native crash. Any path
                // that reaches this finally means Kotlin is still alive, so
                // delete it — otherwise the next launch misreports "GPU crash"
                // and forces CPU.
                crumb?.let { c ->
                    withContext(NonCancellable + Dispatchers.IO) { c.delete() }
                }
            }
        }
    }
}
