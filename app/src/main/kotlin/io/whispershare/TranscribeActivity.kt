package io.whispershare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import io.whispershare.ui.TranscribeScreen
import io.whispershare.ui.TranscribeUiState
import io.whispershare.ui.theme.WhisperShareTheme
import kotlinx.coroutines.flow.StateFlow

class TranscribeActivity : ComponentActivity() {

    private val vm: TranscribeViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional — service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = extractUri(intent)
        val viewResultOnly = uri == null && intent.action == ACTION_VIEW_RESULT
        if (uri == null && !viewResultOnly) {
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

        // Only kick off on a fresh launch — a config-change recreation must not
        // cancel and restart the in-flight run in the service.
        if (uri != null && savedInstanceState == null) {
            startTranscription(uri)
        }

        setContent {
            WhisperShareTheme {
                val state by vm.state.collectAsState()
                // Opened from a notification after the process was recycled:
                // the in-memory result is gone, say so instead of "Starting…".
                val display =
                    if (viewResultOnly && state is TranscribeUiState.Idle)
                        TranscribeUiState.Error(stringResource(R.string.error_result_gone))
                    else state
                TranscribeScreen(
                    state = display,
                    onClose = { finish() },
                    onCopy = { text -> ShareUtils.copy(this, text) },
                    onShare = { text -> ShareUtils.share(this, text) },
                    onCancel = { vm.cancel() },
                    onRetry = uri?.let { u -> { startTranscription(u) } }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { startTranscription(it) }
    }

    private fun startTranscription(uri: Uri) {
        vm.start(uri)
        // POST_NOTIFICATIONS is runtime on 33+. Ask, but never block on it —
        // the service runs fine without a visible notification.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            Intent.ACTION_VIEW -> intent.data
            ACTION_VIEW_RESULT -> null
            else -> intent.data ?: intent.parcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(name) as? T

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)

    companion object {
        /** Fired by the service's notifications: show current/last state, start nothing. */
        const val ACTION_VIEW_RESULT = "io.whispershare.action.VIEW_RESULT"
    }
}

/**
 * Thin adapter between the UI and [TranscriptionService], which owns the
 * pipeline (so it survives this activity finishing). State is the service's
 * process-wide StateFlow re-exposed unchanged.
 */
class TranscribeViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {

    val state: StateFlow<TranscribeUiState> = TranscriptionService.state

    fun start(uri: Uri) = TranscriptionService.start(getApplication(), uri)

    fun cancel() = TranscriptionService.cancel(getApplication())
}
