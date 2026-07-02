package io.whispershare

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.coroutines.coroutineContext

/**
 * Decodes a content URI (whatever the messenger sends — usually OGG/OPUS for WhatsApp,
 * Telegram, Signal; AAC/M4A or MP3 for others) to mono 16 kHz float PCM, ready for whisper.cpp.
 *
 * Uses Android's stock MediaExtractor + MediaCodec, so no FFmpeg dependency.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16_000
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    // Each fully stalled loop iteration blocks ~10-20 ms in the two dequeue calls,
    // so ~250 consecutive no-progress iterations ≈ 5 s of a wedged decoder.
    private const val MAX_NO_PROGRESS_ITERATIONS = 250

    suspend fun decodeToPcm(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        // MediaExtractor needs either a file path or an FD. Content URIs work via openAssetFileDescriptor.
        val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        pfd.use { afd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                decodeWithExtractor(extractor)
            } finally {
                extractor.release()
            }
        }
    }

    /**
     * Some sources (e.g. tricky OGG containers from old WhatsApp builds) fail with the FD path.
     * Fallback: copy to a temp file and retry.
     */
    suspend fun decodeToPcmWithFallback(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        try {
            decodeToPcm(context, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Direct decode failed (${e.message}), falling back to temp-file copy")
            val temp = File.createTempFile("share_audio_", ".bin", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("Failed to open stream for $uri")

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(temp.absolutePath)
                    decodeWithExtractor(extractor)
                } finally {
                    extractor.release()
                }
            } finally {
                temp.delete()
            }
        }
    }

    private suspend fun decodeWithExtractor(extractor: MediaExtractor): FloatArray {
        // Find the first audio track.
        var audioTrack = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrack = i
                inputFormat = format
                break
            }
        }
        require(audioTrack >= 0 && inputFormat != null) { "No audio track found" }
        extractor.selectTrack(audioTrack)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val srcRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        Log.i(TAG, "Decoding $mime  rate=$srcRate ch=$srcChannels")

        // The decoder's OUTPUT format is authoritative for the produced PCM: HE-AAC/SBR
        // streams (common in WhatsApp/Telegram voice notes) decode at a different rate
        // than the container declares. Start from the container values, then update on
        // INFO_OUTPUT_FORMAT_CHANGED.
        var outRate = srcRate
        var outChannels = srcChannels

        val pcm = PcmBuffer(srcRate * srcChannels * 30) // ~30s headroom
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var noProgressCount = 0

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            while (!sawOutputEOS) {
                coroutineContext.ensureActive()
                var progressed = false

                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        progressed = true
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        Log.d(TAG, "Output format: $newFormat (rate=$outRate ch=$outChannels)")
                    }
                    outIdx >= 0 -> {
                        progressed = true
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            pcm.append(outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                }

                if (progressed) {
                    noProgressCount = 0
                } else if (++noProgressCount >= MAX_NO_PROGRESS_ITERATIONS) {
                    throw IllegalStateException(
                        "Decoder stalled: no progress after $MAX_NO_PROGRESS_ITERATIONS iterations (~5s) for $mime"
                    )
                }
            }

            codec.stop()
        } finally {
            codec.release()
        }

        // Convert Short PCM to float, downmix to mono, resample to 16 kHz.
        val mono = if (outChannels == 1) shortsToFloats(pcm.data, pcm.size) else downmix(pcm.data, pcm.size, outChannels)
        return resampleLinear(mono, outRate, TARGET_RATE)
    }

    /** Growable ShortArray accumulator (double-on-grow) — avoids boxing millions of Shorts. */
    private class PcmBuffer(initialCapacity: Int) {
        var data = ShortArray(maxOf(initialCapacity, 1024)); private set
        var size = 0; private set

        fun append(src: ShortBuffer) {
            val n = src.remaining()
            if (size + n > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + n))
            }
            src.get(data, size, n)
            size += n
        }
    }

    @VisibleForTesting
    internal fun shortsToFloats(src: ShortArray, size: Int): FloatArray {
        val out = FloatArray(size)
        for (i in 0 until size) out[i] = src[i] / 32768f
        return out
    }

    @VisibleForTesting
    internal fun downmix(src: ShortArray, size: Int, channels: Int): FloatArray {
        val frames = (size + channels - 1) / channels // tolerate a trailing partial frame
        val out = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0
            var count = 0
            while (count < channels && idx < size) {
                sum += src[idx++].toInt()
                count++
            }
            out[f] = (sum.toFloat() / count) / 32768f
        }
        return out
    }

    /**
     * Resampler. When downsampling, box-averages the source window before decimation to
     * suppress aliasing; when upsampling, linear-interpolates. Good enough for speech;
     * whisper preprocesses again internally.
     */
    @VisibleForTesting
    internal fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        if (ratio > 1.0) {
            for (i in 0 until outLen) {
                val start = (i * ratio).toInt()
                val end = ((i + 1) * ratio).toInt().coerceAtMost(input.size)
                var sum = 0f
                for (j in start until end) sum += input[j]
                out[i] = sum / (end - start)
            }
        } else {
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val i0 = srcPos.toInt()
                val i1 = (i0 + 1).coerceAtMost(input.size - 1)
                val frac = (srcPos - i0).toFloat()
                out[i] = input[i0] * (1f - frac) + input[i1] * frac
            }
        }
        return out
    }
}
