package io.whispershare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import io.whispershare.ui.TranscribeUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that owns the transcription pipeline (decode → whisper),
 * so it survives the activity being backgrounded or finished.
 *
 * One transcription at a time by design: state is published through the
 * companion [state] StateFlow that [TranscribeViewModel] re-exposes to the UI.
 * A new [ACTION_TRANSCRIBE] intent cancels the in-flight run (matching the old
 * ViewModel semantics), and cancellation — from the UI button or the
 * notification action — propagates to the native engine via the Wave-1
 * abort plumbing in [WhisperEngine.transcribe].
 */
class TranscriptionService : Service() {

    private val prefs by lazy { AppPreferences(this) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    /**
     * Main (not immediate) so a launch from onStartCommand always dispatches:
     * currentJob is assigned before any coroutine code runs.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null
    @Volatile private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                getString(R.string.notification_channel_progress),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                getString(R.string.notification_channel_result),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSCRIBE -> {
                val uri = intent.data
                if (uri != null) {
                    goForeground()
                    beginTranscription(uri)
                } else if (currentJob == null) {
                    stopSelf()
                }
            }
            ACTION_CANCEL -> cancelCurrent()
            else -> if (currentJob == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** API 35+: mediaProcessing services are stopped after ~6h — shut down cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout (type=$fgsType) — cancelling transcription")
        cancelCurrent()
    }

    override fun onDestroy() {
        // Destroyed mid-run (system stop): cancel the pipeline so the engine
        // aborts and the crumb finally-block runs. State mirrors user cancel.
        if (currentJob != null) {
            currentJob = null
            _state.value = TranscribeUiState.Error(getString(R.string.transcription_cancelled))
        }
        scope.cancel()
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
        super.onDestroy()
    }

    // ---------- pipeline lifecycle ----------

    private fun beginTranscription(uri: Uri) {
        currentJob?.cancel()
        currentJob = null
        lastNotifiedPercent = -1
        _state.value = TranscribeUiState.Stage(getString(R.string.stage_loading_model), progress = null)
        val job = scope.launch {
            val finalState = try {
                runPipeline(uri)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                TranscribeUiState.Error(t.message ?: t.javaClass.simpleName)
            }
            _state.value = finalState
            when (finalState) {
                is TranscribeUiState.Done ->
                    showFinalNotification(getString(R.string.notification_done_title), finalState.text)
                is TranscribeUiState.Error ->
                    showFinalNotification(getString(R.string.notification_error_title), finalState.message)
                else -> Unit
            }
            // Only stop if we're still the active run — a newer intent may
            // have replaced us while we were finishing.
            if (currentJob === coroutineContext.job) {
                currentJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        currentJob = job
    }

    private fun cancelCurrent() {
        val job = currentJob
        currentJob = null
        if (job != null) {
            job.cancel()
            _state.value = TranscribeUiState.Error(getString(R.string.transcription_cancelled))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The transcription pipeline, moved wholesale from TranscribeViewModel.
     * Returns the terminal state (Done or Error); intermediate Stage/Streaming
     * states are pushed to [_state] directly. Throws CancellationException on
     * cancel — callers must let it propagate.
     */
    private suspend fun runPipeline(uri: Uri): TranscribeUiState {
        // Native callbacks keep firing until the abort flag is polled;
        // guard them with this so a stale segment can't overwrite the
        // "cancelled" state written by cancelCurrent().
        val self = kotlin.coroutines.coroutineContext.job
        var crumb: File? = null
        try {
            val ctx = applicationContext
            val entry = withContext(Dispatchers.IO) {
                ModelManager.entryById(ctx, prefs.selectedModelId)
            }
            if (entry == null) {
                return TranscribeUiState.Error(getString(R.string.error_model_unavailable))
            }
            val modelFile = ModelManager.fileFor(ctx, entry)
            if (!withContext(Dispatchers.IO) { modelFile.exists() }) {
                return TranscribeUiState.Error(
                    getString(R.string.error_model_not_downloaded, entry.displayName)
                )
            }

            var useGpu = prefs.useGpu
            var notice: String? = null

            // Pick up a one-shot notice from a previous-run GPU crash.
            if (prefs.gpuCrashedNotice) {
                prefs.gpuCrashedNotice = false
                notice = getString(R.string.notice_gpu_crash_previous)
            }

            val loadResult = WhisperEngine.load(modelFile, useGpu = useGpu)
            if (loadResult.isFailure && useGpu) {
                // GPU init blew up (e.g. Mali driver) — fall back to CPU and remember it.
                prefs.useGpu = false
                useGpu = false
                notice = getString(R.string.notice_gpu_init_failed)
                WhisperEngine.load(modelFile, useGpu = false)
                    .getOrElse {
                        return TranscribeUiState.Error(getString(R.string.error_load_model, it.message ?: ""))
                    }
            } else if (loadResult.isFailure) {
                return TranscribeUiState.Error(
                    getString(R.string.error_load_model, loadResult.exceptionOrNull()?.message ?: "")
                )
            }

            _state.value = TranscribeUiState.Stage(getString(R.string.stage_decoding_audio), null)
            val pcm = AudioDecoder.decodeToPcmWithFallback(ctx, uri)
            val durationSec = pcm.size / 16_000.0

            suspend fun runOnce(): String {
                _state.value = TranscribeUiState.Stage(
                    getString(R.string.transcribe_progress_duration, durationSec),
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
                            updateProgressNotification(pct.coerceIn(0f, 1f))
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
                notice = getString(R.string.notice_gpu_error_switched, errorNow)
                WhisperEngine.release()
                WhisperEngine.load(modelFile, useGpu = false).getOrElse {
                    return TranscribeUiState.Error(getString(R.string.error_reload_cpu, it.message ?: ""))
                }
                text = runOnce()
            }
            val ms = System.currentTimeMillis() - started

            return TranscribeUiState.Done(
                text = listOfNotNull(notice, text.ifBlank { getString(R.string.no_speech_detected) })
                    .joinToString("\n\n"),
                durationSec = durationSec,
                elapsedMs = ms,
                backend = WhisperEngine.activeBackend
            )
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

    // ---------- notifications ----------

    private fun goForeground() {
        val notification = buildProgressNotification(null)
        try {
            when {
                Build.VERSION.SDK_INT >= 35 ->
                    startForeground(
                        NOTIFICATION_ID_PROGRESS, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    )
                Build.VERSION.SDK_INT >= 34 ->
                    // mediaProcessing doesn't exist before API 35; dataSync is
                    // also declared in the manifest as the API-34 fallback.
                    startForeground(
                        NOTIFICATION_ID_PROGRESS, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                else ->
                    startForeground(NOTIFICATION_ID_PROGRESS, notification)
            }
        } catch (t: Throwable) {
            // Type constraints rejected us on this API level — degrade to a
            // plain started service rather than failing the transcription.
            Log.w(TAG, "startForeground failed — continuing without foreground priority", t)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, TranscribeActivity::class.java)
            .setAction(TranscribeActivity.ACTION_VIEW_RESULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(progress: Float?): Notification {
        val cancelPending = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(getString(R.string.notification_transcribing_title))
            .apply {
                if (progress != null) {
                    val pct = (progress * 100).toInt()
                    setProgress(100, pct, false)
                    setContentText(getString(R.string.notification_progress_percent, pct))
                } else {
                    setProgress(0, 0, true)
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.cancel_transcription), cancelPending
                ).build()
            )
            .build()
    }

    /** Called from the native progress callback (IO thread); throttled per percent. */
    private fun updateProgressNotification(progress: Float) {
        val pct = (progress * 100).toInt()
        if (pct == lastNotifiedPercent) return
        lastNotifiedPercent = pct
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(progress))
    }

    private fun showFinalNotification(title: String, text: String) {
        val notification = Notification.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_RESULT, notification)
    }

    companion object {
        private const val TAG = "TranscriptionService"

        const val ACTION_TRANSCRIBE = "io.whispershare.action.TRANSCRIBE"
        const val ACTION_CANCEL = "io.whispershare.action.CANCEL_TRANSCRIPTION"

        private const val CHANNEL_PROGRESS = "transcribe_progress"
        private const val CHANNEL_RESULT = "transcribe_result"
        private const val NOTIFICATION_ID_PROGRESS = 1
        private const val NOTIFICATION_ID_RESULT = 2

        private val _state = MutableStateFlow<TranscribeUiState>(TranscribeUiState.Idle)

        /** Published UI state; survives activity death for the life of the process. */
        val state: StateFlow<TranscribeUiState> = _state.asStateFlow()

        /**
         * Start (or replace) a transcription. The read grant on [uri] is
         * re-granted to the service via the intent so access outlives the
         * sharing activity.
         */
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, TranscriptionService::class.java)
                .setAction(ACTION_TRANSCRIBE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
