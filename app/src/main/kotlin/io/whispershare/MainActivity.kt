package io.whispershare

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
                    onToggleGpu = vm::toggleGpu,
                    onSetLanguage = vm::setLanguage,
                    onToggleTranslate = vm::toggleTranslate
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
        return HomeUiState(
            installed = ModelManager.listInstalled(ctx).map { it.name }.toSet(),
            selected = prefs.selectedModel.name,
            downloading = emptyMap(),
            useGpu = prefs.useGpu,
            language = prefs.language,
            translateToEnglish = prefs.translateToEnglish
        )
    }

    fun refresh() {
        val ctx = getApplication<android.app.Application>()
        _state.update { it.copy(installed = ModelManager.listInstalled(ctx).map { m -> m.name }.toSet()) }
    }

    fun selectModel(name: String) {
        runCatching { ModelManager.Model.valueOf(name) }.onSuccess {
            prefs.selectedModel = it
            _state.update { s -> s.copy(selected = name) }
        }
    }

    fun download(name: String) {
        val model = runCatching { ModelManager.Model.valueOf(name) }.getOrNull() ?: return
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch {
            try {
                ModelManager.download(ctx, model).collect { pct ->
                    _state.update { s ->
                        val map = s.downloading.toMutableMap()
                        if (pct < 0) map.remove(name) else map[name] = pct
                        s.copy(downloading = map)
                    }
                }
            } catch (t: Throwable) {
                _state.update { s ->
                    val map = s.downloading.toMutableMap()
                    map.remove(name)
                    s.copy(downloading = map, errorMessage = t.message)
                }
            } finally {
                refresh()
            }
        }
    }

    fun delete(name: String) {
        val model = runCatching { ModelManager.Model.valueOf(name) }.getOrNull() ?: return
        ModelManager.delete(getApplication(), model)
        refresh()
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
}
