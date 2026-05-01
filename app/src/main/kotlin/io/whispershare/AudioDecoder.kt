package io.whispershare

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

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

    private fun decodeWithExtractor(extractor: MediaExtractor): FloatArray {
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

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val pcm = ArrayList<Short>(srcRate * 30) // ~30s headroom
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx >= 0) {
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
                    Log.d(TAG, "Output format: ${codec.outputFormat}")
                }
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        appendShorts(pcm, shorts)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }
            }
        }

        codec.stop()
        codec.release()

        // Convert Short PCM to float, downmix to mono, resample to 16 kHz.
        val mono = if (srcChannels == 1) shortsToFloats(pcm) else downmix(pcm, srcChannels)
        return resampleLinear(mono, srcRate, TARGET_RATE)
    }

    private fun appendShorts(dst: ArrayList<Short>, src: ShortBuffer) {
        val n = src.remaining()
        dst.ensureCapacity(dst.size + n)
        repeat(n) { dst.add(src.get()) }
    }

    private fun shortsToFloats(src: ArrayList<Short>): FloatArray {
        val out = FloatArray(src.size)
        for (i in src.indices) out[i] = src[i] / 32768f
        return out
    }

    private fun downmix(src: ArrayList<Short>, channels: Int): FloatArray {
        val frames = src.size / channels
        val out = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += src[idx++].toInt()
            out[f] = (sum.toFloat() / channels) / 32768f
        }
        return out
    }

    /** Linear-interpolation resampler. Good enough for speech; whisper preprocesses again internally. */
    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }
}
