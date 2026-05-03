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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private fun initial(): HomeUiState {
        val ctx = getApplication<android.app.Application>()
        val entries = ModelManager.listAll(ctx)
        return HomeUiState(
            entries = entries,
            installed = entries.filter { ModelManager.isDownloaded(ctx, it) }.map { it.id }.toSet(),
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
    }

    fun refresh() {
        val ctx = getApplication<android.app.Application>()
        val entries = ModelManager.listAll(ctx)
        _state.update {
            it.copy(
                entries = entries,
                installed = entries.filter { e -> ModelManager.isDownloaded(ctx, e) }.map { e -> e.id }.toSet()
            )
        }
    }

    fun selectModel(id: String) {
        val ctx = getApplication<android.app.Application>()
        if (ModelManager.entryById(ctx, id) == null) return
        prefs.selectedModelId = id
        _state.update { it.copy(selectedId = id) }
    }

    fun download(id: String) {
        val model = ModelManager.entryById(getApplication(), id) as? ModelManager.BuiltInModel ?: return
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            try {
                ModelManager.download(ctx, model, verify = !prefs.skipModelVerification).collect { pct ->
                    _state.update { s ->
                        val map = s.downloading.toMutableMap()
                        if (pct < 0) map.remove(id) else map[id] = pct
                        s.copy(downloading = map)
                    }
                }
            } catch (t: Throwable) {
                _state.update { s ->
                    val map = s.downloading.toMutableMap()
                    map.remove(id)
                    s.copy(downloading = map, errorMessage = t.message)
                }
            } finally {
                refresh()
            }
        }
    }

    fun delete(id: String) {
        val entry = ModelManager.entryById(getApplication(), id) ?: return
        ModelManager.delete(getApplication(), entry)
        refresh()
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
