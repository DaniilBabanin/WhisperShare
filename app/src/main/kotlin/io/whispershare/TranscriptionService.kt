package io.whispershare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
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

    /** "File 2 of 3" while a multi-file batch runs; null for single files. */
    @Volatile private var currentFileLabel: String? = null

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
                val uris = extractUris(intent)
                if (uris.isNotEmpty()) {
                    goForeground()
                    beginTranscription(uris)
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

    /** All URIs of the batch: ClipData items (multi-share) or intent.data (single). */
    private fun extractUris(intent: Intent): List<Uri> {
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (uris.isNotEmpty()) return uris
        }
        return listOfNotNull(intent.data)
    }

    private fun beginTranscription(uris: List<Uri>) {
        currentJob?.cancel()
        currentJob = null
        lastNotifiedPercent = -1
        currentFileLabel = null
        _state.value = TranscribeUiState.Stage(getString(R.string.stage_loading_model), progress = null)
        val job = scope.launch {
            var finalState = try {
                runPipeline(uris)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                TranscribeUiState.Error(t.message ?: t.javaClass.simpleName)
            }
            if (finalState is TranscribeUiState.Done) {
                finalState = saveToHistoryIfEnabled(uris, finalState)
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
     * The transcription pipeline. The model is resolved and loaded once, then
     * each URI is transcribed sequentially (one engine, serialized by design).
     * Multi-file batches join results under per-file headers; a failing file
     * gets an error marker and the batch continues. Returns the terminal state
     * (Done or Error); intermediate Stage/Streaming states are pushed to
     * [_state] directly. Throws CancellationException on cancel — callers
     * must let it propagate (cancel aborts the whole batch).
     */
    private suspend fun runPipeline(uris: List<Uri>): TranscribeUiState {
        // Native callbacks keep firing until the abort flag is polled;
        // guard them with this so a stale segment can't overwrite the
        // "cancelled" state written by cancelCurrent().
        val self = kotlin.coroutines.coroutineContext.job
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

        /**
         * Transcribe one file; returns its text and duration. Streaming
         * states carry [prefix] + partial so the UI shows the running
         * combined transcript across the batch. Throws on failure —
         * the loop below decides whether that sinks the batch.
         */
        suspend fun transcribeOne(uri: Uri, prefix: String, fileLabel: String?): Pair<String, Double> {
            var crumb: File? = null
            try {
                _state.value = TranscribeUiState.Stage(
                    getString(R.string.stage_decoding_audio), null, fileLabel
                )
                val pcm = AudioDecoder.decodeToPcmWithFallback(ctx, uri)
                val durationSec = pcm.size / 16_000.0

                suspend fun runOnce(): String {
                    _state.value = TranscribeUiState.Stage(
                        getString(R.string.transcribe_progress_duration, durationSec),
                        0f,
                        fileLabel
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
                            val partial = prefix + builder.toString()
                            if (self.isActive) {
                                _state.update { cur ->
                                    TranscribeUiState.Streaming(
                                        partial = partial,
                                        durationSec = durationSec,
                                        progress = (cur as? TranscribeUiState.Streaming)?.progress,
                                        fileLabel = fileLabel
                                    )
                                }
                            }
                        },
                        onProgress = { pct ->
                            if (self.isActive) {
                                _state.update { cur ->
                                    TranscribeUiState.Streaming(
                                        partial = (cur as? TranscribeUiState.Streaming)?.partial ?: prefix,
                                        durationSec = durationSec,
                                        progress = pct.coerceIn(0f, 1f),
                                        fileLabel = fileLabel
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
                        throw IllegalStateException(getString(R.string.error_reload_cpu, it.message ?: ""))
                    }
                    text = runOnce()
                }
                return text to durationSec
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

        val multi = uris.size > 1
        val combined = StringBuilder()
        var totalDurationSec = 0.0
        // Detected-language codes of the successful files, only collected when
        // the user's language setting is auto-detect. Shown in Done only when
        // unambiguous (all files agree); mixed-language batches show nothing
        // rather than a misleading single language.
        val autoDetect = prefs.language.isBlank()
        val detectedCodes = mutableSetOf<String>()
        val started = System.currentTimeMillis()
        for ((index, uri) in uris.withIndex()) {
            val fileLabel =
                if (multi) getString(R.string.batch_file_position, index + 1, uris.size) else null
            currentFileLabel = fileLabel
            lastNotifiedPercent = -1
            if (multi) {
                val name = withContext(Dispatchers.IO) { displayNameFor(ctx, uri) }
                    ?: getString(R.string.batch_fallback_name, index + 1)
                if (combined.isNotEmpty()) combined.append("\n\n")
                combined.append(getString(R.string.batch_separator, name)).append('\n')
            }
            try {
                val (text, durationSec) = transcribeOne(uri, combined.toString(), fileLabel)
                totalDurationSec += durationSec
                combined.append(text.ifBlank { getString(R.string.no_speech_detected) })
                if (autoDetect) {
                    WhisperEngine.detectedLanguage()
                        .takeIf { it.isNotBlank() }
                        ?.let { detectedCodes.add(it) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Single file: keep the old contract — the whole run fails.
                // Batch: mark this file and keep going with the next one.
                if (!multi) throw t
                combined.append(getString(R.string.batch_file_error, t.message ?: t.javaClass.simpleName))
            }
        }
        val ms = System.currentTimeMillis() - started

        return TranscribeUiState.Done(
            text = listOfNotNull(notice, combined.toString()).joinToString("\n\n"),
            durationSec = totalDurationSec,
            elapsedMs = ms,
            backend = WhisperEngine.activeBackend,
            detectedLanguage = detectedCodes.singleOrNull()
        )
    }

    /**
     * Appends the finished run to the opt-in local history — a single entry
     * even for batches, carrying the combined text. No-op when the user hasn't
     * opted in (the default): nothing is ever written. Returns the state marked
     * [TranscribeUiState.Done.savedToHistory] on success; a history I/O failure
     * only logs — it must never sink a successful transcription.
     */
    private suspend fun saveToHistoryIfEnabled(
        uris: List<Uri>,
        done: TranscribeUiState.Done
    ): TranscribeUiState.Done {
        val ctx = applicationContext
        return try {
            withContext(Dispatchers.IO) {
                if (!TranscriptHistory.isEnabled(ctx)) return@withContext done
                val sourceName =
                    if (uris.size > 1) getString(R.string.history_source_files, uris.size)
                    else displayNameFor(ctx, uris.first())
                        ?: getString(R.string.history_source_unknown)
                TranscriptHistory.forApp(ctx).append(
                    TranscriptHistory.Entry(
                        timestamp = System.currentTimeMillis(),
                        sourceName = sourceName,
                        text = done.text,
                        durationSec = done.durationSec,
                        detectedLanguage = done.detectedLanguage
                    )
                )
                done.copy(savedToHistory = true)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save transcript to history", t)
            done
        }
    }

    /** DISPLAY_NAME via ContentResolver; null when the provider doesn't offer one. */
    private fun displayNameFor(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.takeIf { it.isNotBlank() }
            } else null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "DISPLAY_NAME query failed for $uri", t)
        null
    }

    // ---------- notifications ----------

    private fun goForeground() {
        val notification = buildProgressNotification(null, null)
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

    private fun buildProgressNotification(progress: Float?, fileLabel: String?): Notification {
        val cancelPending = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(getString(R.string.notification_transcribing_title))
            .apply {
                // Batch position ("File 2 of 3") in the header, next to the app name.
                if (fileLabel != null) setSubText(fileLabel)
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
        notificationManager.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(progress, currentFileLabel)
        )
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
         * Start (or replace) a transcription of one or more files. The read
         * grants on [uris] are re-granted to the service via the intent so
         * access outlives the sharing activity: FLAG_GRANT_READ_URI_PERMISSION
         * operates on intent.data AND on every URI in the intent's ClipData
         * (see Intent.setClipData docs) — ClipData is the grant-carrying
         * channel for multiple content: URIs.
         */
        fun start(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val intent = Intent(context, TranscriptionService::class.java)
                .setAction(ACTION_TRANSCRIBE)
                .setData(uris.first())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri(null, uris.first())
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            intent.clipData = clip
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
