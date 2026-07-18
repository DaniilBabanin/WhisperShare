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
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Decodes a content URI (whatever the messenger sends — usually OGG/OPUS for WhatsApp,
 * Telegram, Signal; AAC/M4A or MP3 for others) to a mono 16 kHz little-endian int16 PCM
 * *file*, ready for whisper.cpp to read natively.
 *
 * Fully streaming: chunk → downmix → resample → file, so RAM stays bounded whatever the
 * input size. The previous whole-file FloatArray pipeline held 3-4 full-length buffers at
 * once (the source-rate accumulator alone was ~690 MB for an hour of 48 kHz stereo) and
 * the equivalent path in soundscript OOM'd on a ~300 MB hi-res WAV (2026-07-13).
 *
 * Uses Android's stock MediaExtractor + MediaCodec, so no FFmpeg dependency.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    internal const val TARGET_RATE = 16_000
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val CHUNK_BYTES = 1 shl 16

    // Each fully stalled loop iteration blocks ~10-20 ms in the two dequeue calls,
    // so ~250 consecutive no-progress iterations ≈ 5 s of a wedged decoder.
    private const val MAX_NO_PROGRESS_ITERATIONS = 250

    /** A decoded clip: 16 kHz mono int16 LE PCM on disk. Caller deletes [file] when done. */
    class DecodedPcm(val file: File, val sampleCount: Long) {
        val durationSec: Double get() = sampleCount / TARGET_RATE.toDouble()
    }

    suspend fun decodeToPcm(context: Context, uri: Uri): DecodedPcm = withContext(Dispatchers.IO) {
        // MediaExtractor needs either a file path or an FD. Content URIs work via openAssetFileDescriptor.
        val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        pfd.use { afd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                decodeToTempFile(context, extractor)
            } finally {
                extractor.release()
            }
        }
    }

    /**
     * Some sources (e.g. tricky OGG containers from old WhatsApp builds) fail with the FD path.
     * Fallback: copy to a temp file and retry.
     */
    suspend fun decodeToPcmWithFallback(context: Context, uri: Uri): DecodedPcm = withContext(Dispatchers.IO) {
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
                    decodeToTempFile(context, extractor)
                } finally {
                    extractor.release()
                }
            } finally {
                temp.delete()
            }
        }
    }

    /** Decode into a fresh temp PCM file; the file is removed again if decoding throws. */
    private suspend fun decodeToTempFile(context: Context, extractor: MediaExtractor): DecodedPcm {
        val outFile = File.createTempFile("pcm16k_", ".pcm", context.cacheDir)
        try {
            val samples = decodeWithExtractor(extractor, outFile)
            return DecodedPcm(outFile, samples)
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        }
    }

    /** Returns the number of 16 kHz mono samples written to [outFile]. */
    private suspend fun decodeWithExtractor(extractor: MediaExtractor, outFile: File): Long {
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
        Log.i(TAG, "Decoding $mime  rate=${inputFormat.rate()} ch=${inputFormat.channels()} enc=${inputFormat.encoding()}")

        var written = 0L
        outFile.outputStream().buffered(CHUNK_BYTES).use { out ->
            val emit: (Float) -> Unit = { s ->
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                written++
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                streamRaw(extractor, inputFormat, emit)
            } else {
                streamCompressed(extractor, inputFormat, mime, emit)
            }
        }
        return written
    }

    /**
     * Raw PCM track (WAV) — feed extractor chunks straight to the converter, no codec.
     * This path is what makes hi-res WAVs work: the track format reports the real encoding
     * (24-bit packed / 32-bit int / float), which the converter decodes width-aware.
     */
    private suspend fun streamRaw(extractor: MediaExtractor, format: MediaFormat, emit: (Float) -> Unit) {
        val converter = PcmChunkConverter(format.rate(), format.channels(), format.encoding(), emit)
        val buf = ByteBuffer.allocate(CHUNK_BYTES)
        val arr = ByteArray(CHUNK_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) break
            buf.get(arr, 0, n)
            converter.feed(arr, 0, n)
            buf.clear()
            extractor.advance()
        }
        converter.finish()
    }

    /** Compressed track (opus/aac/mp3/flac…) — MediaCodec decode loop, converted chunk-wise. */
    private suspend fun streamCompressed(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        emit: (Float) -> Unit
    ) {
        // The decoder's OUTPUT format is authoritative for the produced PCM: HE-AAC/SBR
        // streams (common in WhatsApp/Telegram voice notes) decode at a different rate
        // than the container declares. Start from the container values, then update on
        // INFO_OUTPUT_FORMAT_CHANGED; the converter is built lazily once data flows.
        var outRate = inputFormat.rate()
        var outChannels = inputFormat.channels()
        var outEncoding = inputFormat.encoding()
        var converter: PcmChunkConverter? = null
        var chunk = ByteArray(CHUNK_BYTES)

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
                        outRate = newFormat.rate()
                        outChannels = newFormat.channels()
                        outEncoding = newFormat.encoding()
                        Log.d(TAG, "Output format: $newFormat (rate=$outRate ch=$outChannels enc=$outEncoding)")
                    }
                    outIdx >= 0 -> {
                        progressed = true
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            if (converter == null) {
                                converter = PcmChunkConverter(outRate, outChannels, outEncoding, emit)
                            }
                            if (info.size > chunk.size) chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(chunk, 0, info.size)
                            converter.feed(chunk, 0, info.size)
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
        converter?.finish()
    }

    private fun MediaFormat.rate() =
        if (containsKey(MediaFormat.KEY_SAMPLE_RATE)) getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_RATE

    private fun MediaFormat.channels() =
        if (containsKey(MediaFormat.KEY_CHANNEL_COUNT)) getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

    private fun MediaFormat.encoding() =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) getInteger(MediaFormat.KEY_PCM_ENCODING) else 2 // ENCODING_PCM_16BIT

    /**
     * Incremental PCM converter: interleaved little-endian bytes in (any chunk boundaries —
     * partial frames carry over), 16 kHz mono float samples out via [onSample].
     *
     * Handles 16-bit, float, 24-bit packed and 32-bit int PCM (AudioFormat encodings
     * 2/4/21/22 — hi-res WAVs report 21/22 and decode as garbage when read pairwise as
     * shorts; hit in soundscript with a 311 MB studio WAV, 2026-07-13).
     *
     * Resampling: when downsampling, box-averages the source window before decimation to
     * suppress aliasing; when upsampling, linear-interpolates. Good enough for speech;
     * whisper preprocesses again internally.
     */
    @VisibleForTesting
    internal class PcmChunkConverter(
        rate: Int,
        private val channels: Int,
        private val encoding: Int,
        private val onSample: (Float) -> Unit,
    ) {
        private val bytesPerSample = when (encoding) {
            4, 22 -> 4 // ENCODING_PCM_FLOAT / ENCODING_PCM_32BIT
            21 -> 3    // ENCODING_PCM_24BIT_PACKED
            else -> 2  // ENCODING_PCM_16BIT
        }
        private val frameBytes = bytesPerSample * channels
        private val carry = ByteArray(frameBytes)
        private var carryLen = 0
        private val passthrough = rate == TARGET_RATE
        private val ratio = rate.toDouble() / TARGET_RATE
        private var framesIn = 0L
        private var samplesOut = 0L
        // Downsample state: running box-average of the current output window.
        private var boxSum = 0f
        private var boxCount = 0
        private var boxBoundary = ratio.toLong()
        // Upsample state: previous input sample for the interpolation.
        private var last = 0f

        fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
            if (channels < 1) return
            var i = off
            val end = off + len
            if (carryLen > 0) {
                while (carryLen < frameBytes && i < end) carry[carryLen++] = data[i++]
                if (carryLen == frameBytes) {
                    frame(carry, 0)
                    carryLen = 0
                } else return
            }
            while (end - i >= frameBytes) {
                frame(data, i)
                i += frameBytes
            }
            while (i < end) carry[carryLen++] = data[i++]
        }

        /**
         * Flush the resampler tail so the output length matches floor(framesIn / ratio)
         * exactly: the final partial box window when downsampling, the positions clamped
         * to the last sample when upsampling.
         */
        fun finish() {
            if (passthrough) return
            val expected = (framesIn / ratio).toLong()
            if (ratio > 1.0) {
                if (boxCount > 0 && samplesOut < expected) emitOut(boxSum / boxCount)
            } else {
                while (samplesOut < expected) emitOut(last)
            }
        }

        private fun frame(b: ByteArray, off: Int) {
            var s = 0f
            var p = off
            repeat(channels) {
                s += sampleAt(b, p)
                p += bytesPerSample
            }
            push(s / channels)
        }

        // The last byte's toInt() sign-extends, giving each width its sign bit.
        private fun sampleAt(b: ByteArray, p: Int): Float = when (encoding) {
            4 -> Float.fromBits(
                (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                    ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
            )
            21 -> ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                (b[p + 2].toInt() shl 16)) / 8388608f
            22 -> ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)) / 2147483648f
            else -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)) / 32768f
        }

        private fun push(s: Float) {
            if (passthrough) {
                emitOut(s)
                return
            }
            framesIn++
            if (ratio > 1.0) {
                // Box-average every input frame of the window [k*ratio, (k+1)*ratio).
                boxSum += s
                boxCount++
                if (framesIn >= boxBoundary) {
                    emitOut(boxSum / boxCount)
                    boxSum = 0f
                    boxCount = 0
                    boxBoundary = ((samplesOut + 1) * ratio).toLong()
                }
            } else {
                // Emit every output position that falls before this input sample.
                if (framesIn == 1L) {
                    last = s
                    return
                }
                val inIdx = framesIn - 1
                while (true) {
                    val p = samplesOut * ratio
                    if (p >= inIdx) break
                    val frac = (p - (inIdx - 1)).toFloat()
                    emitOut(last * (1 - frac) + s * frac)
                }
                last = s
            }
        }

        private fun emitOut(s: Float) {
            onSample(s)
            samplesOut++
        }
    }
}
