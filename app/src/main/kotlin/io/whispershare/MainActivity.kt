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
                    onSetDeveloperMode = vm::setDeveloperMode,
                    onToggleSkipModelVerification = vm::toggleSkipModelVerification,
                    onOpenBenchmark = {
                        startActivity(Intent(this, BenchmarkActivity::class.java))
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
        developerMode = prefs.developerMode,
        skipModelVerification = prefs.skipModelVerification
    )

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        val ctx = getApplication<android.app.Application>()
        val (entries, installed) = withContext(Dispatchers.IO) {
            val all = ModelManager.listAll(ctx)
            all to all.filter { ModelManager.isDownloaded(ctx, it) }.map { it.id }.toSet()
        }
        _state.update { it.copy(entries = entries, installed = installed) }
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
        // ModelManager.download's finally block removes the .part file.
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
                    _state.update { it.copy(errorMessage = "Import failed: ${t.message}") }
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

    fun setDeveloperMode(enabled: Boolean) {
        prefs.developerMode = enabled
        _state.update { it.copy(developerMode = enabled) }
    }

    fun toggleSkipModelVerification(enabled: Boolean) {
        prefs.skipModelVerification = enabled
        _state.update { it.copy(skipModelVerification = enabled) }
    }
}
