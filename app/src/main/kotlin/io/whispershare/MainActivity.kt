package io.whispershare

import android.content.Intent
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
import io.whispershare.ui.HomeScreen
import io.whispershare.ui.HomeUiState
import io.whispershare.ui.theme.WhisperShareTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.refresh()
        setContent {
            WhisperShareTheme {
                val state by vm.state.collectAsState()
                HomeScreen(
                    state = state,
                    onSelectModel = vm::selectModel,
                    onDownload = vm::download,
                    onCancelDownload = vm::cancelDownload,
                    onDelete = vm::delete,
                    onImportModel = vm::importModel,
                    onToggleGpu = vm::toggleGpu,
                    onSetLanguage = vm::setLanguage,
                    onToggleTranslate = vm::toggleTranslate,
                    onSetThreads = vm::setThreads,
                    onToggleHighQuality = vm::toggleHighQuality,
                    onToggleVad = vm::toggleVad,
                    onDownloadVadModel = vm::downloadVadModel,
                    onCancelVadDownload = vm::cancelVadDownload,
                    onSetDeveloperMode = vm::setDeveloperMode,
                    onToggleSkipModelVerification = vm::toggleSkipModelVerification,
                    onOpenBenchmark = {
                        startActivity(Intent(this, BenchmarkActivity::class.java))
                    },
                    onTranscribeAudio = { uri ->
                        val intent = Intent(this, TranscribeActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            setDataAndType(uri, contentResolver.getType(uri) ?: "audio/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

class HomeViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** In-flight downloads by model id. Guarded by main-thread confinement. */
    private val downloadJobs = mutableMapOf<String, Job>()

    /** Preference-only snapshot; the model list is filled in by [refresh] off-main. */
    private fun initial(): HomeUiState = HomeUiState(
        entries = emptyList(),
        installed = emptySet(),
        downloading = emptyMap(),
        selectedId = prefs.selectedModelId,
        useGpu = prefs.useGpu,
        language = prefs.language,
        translateToEnglish = prefs.translateToEnglish,
        threads = prefs.threads,
        highQuality = prefs.highQuality,
        vadEnabled = prefs.vadEnabled,
        developerMode = prefs.developerMode,
        skipModelVerification = prefs.skipModelVerification
    )

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        val ctx = getApplication<android.app.Application>()
        val (entries, installed, vadInstalled) = withContext(Dispatchers.IO) {
            val all = ModelManager.listAll(ctx)
            Triple(
                all,
                all.filter { ModelManager.isDownloaded(ctx, it) }.map { it.id }.toSet(),
                ModelManager.isVadModelDownloaded(ctx)
            )
        }
        _state.update {
            it.copy(entries = entries, installed = installed, vadModelInstalled = vadInstalled)
        }
    }

    fun selectModel(id: String) {
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            val known = withContext(Dispatchers.IO) { ModelManager.entryById(ctx, id) != null }
            if (!known) return@launch
            prefs.selectedModelId = id
            _state.update { it.copy(selectedId = id) }
        }
    }

    fun download(id: String) {
        // Dedup: a second tap while a download is in flight is ignored
        // (interleaved writers would corrupt the shared .part file).
        if (downloadJobs.containsKey(id)) return
        val ctx = getApplication<android.app.Application>()
        val job = viewModelScope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    ModelManager.entryById(ctx, id)
                } as? ModelManager.BuiltInModel ?: return@launch
                ModelManager.download(ctx, model, verify = !prefs.skipModelVerification)
                    .collect { progress ->
                        _state.update { s -> s.copy(downloading = s.downloading + (id to progress)) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { s -> s.copy(errorMessage = t.message) }
            } finally {
                downloadJobs.remove(id)
                withContext(NonCancellable) {
                    _state.update { s -> s.copy(downloading = s.downloading - id) }
                    refreshInternal()
                }
            }
        }
        downloadJobs[id] = job
    }

    fun cancelDownload(id: String) {
        // ModelManager.download removes the .part file on cancellation
        // (failed downloads keep it so a retry can resume).
        downloadJobs[id]?.cancel()
    }

    fun delete(id: String) {
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val entry = ModelManager.entryById(ctx, id) ?: return@withContext
                ModelManager.delete(ctx, entry)
            }
            refreshInternal()
        }
    }

    fun importModel(uri: Uri, displayName: String, multilingual: Boolean) {
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            ModelManager.importFromUri(ctx, uri, displayName, multilingual)
                .onSuccess { refresh() }
                .onFailure { t ->
                    _state.update {
                        it.copy(errorMessage = ctx.getString(R.string.import_failed, t.message ?: ""))
                    }
                }
        }
    }

    fun toggleGpu(enabled: Boolean) {
        prefs.useGpu = enabled
        _state.update { it.copy(useGpu = enabled) }
    }

    fun setLanguage(code: String) {
        prefs.language = code
        _state.update { it.copy(language = code) }
    }

    fun toggleTranslate(enabled: Boolean) {
        prefs.translateToEnglish = enabled
        _state.update { it.copy(translateToEnglish = enabled) }
    }

    fun setThreads(value: Int) {
        prefs.threads = value
        _state.update { it.copy(threads = value) }
    }

    fun toggleHighQuality(enabled: Boolean) {
        prefs.highQuality = enabled
        _state.update { it.copy(highQuality = enabled) }
    }

    fun toggleVad(enabled: Boolean) {
        prefs.vadEnabled = enabled
        _state.update { it.copy(vadEnabled = enabled) }
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Engine re-validates the file per transcribe, so pushing a
                // not-yet-downloaded path is fine (runs without VAD until then).
                WhisperEngine.vadModelPath =
                    if (enabled) ModelManager.vadModelFile(ctx).absolutePath else null
            }
            if (enabled && !_state.value.vadModelInstalled) downloadVadModel()
        }
    }

    fun downloadVadModel() {
        // Same dedup as [download]: ignore taps while a transfer is in flight.
        if (downloadJobs.containsKey(VAD_JOB_ID)) return
        val ctx = getApplication<android.app.Application>()
        val job = viewModelScope.launch {
            try {
                ModelManager.downloadVadModel(ctx, verify = !prefs.skipModelVerification)
                    .collect { progress ->
                        _state.update { s -> s.copy(vadDownloadProgress = progress) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { s -> s.copy(errorMessage = t.message) }
            } finally {
                downloadJobs.remove(VAD_JOB_ID)
                withContext(NonCancellable) {
                    _state.update { s -> s.copy(vadDownloadProgress = null) }
                    refreshInternal()
                }
            }
        }
        downloadJobs[VAD_JOB_ID] = job
    }

    fun cancelVadDownload() {
        downloadJobs[VAD_JOB_ID]?.cancel()
    }

    fun setDeveloperMode(enabled: Boolean) {
        prefs.developerMode = enabled
        _state.update { it.copy(developerMode = enabled) }
    }

    fun toggleSkipModelVerification(enabled: Boolean) {
        prefs.skipModelVerification = enabled
        _state.update { it.copy(skipModelVerification = enabled) }
    }

    private companion object {
        /** Reserved key in [downloadJobs] for the VAD model (never collides with entry ids). */
        const val VAD_JOB_ID = "vad:silero"
    }
}
