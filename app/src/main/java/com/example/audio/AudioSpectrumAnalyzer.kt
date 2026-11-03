package com.winsmediatama.lyricmakerpro.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AudioAnalysisResult(val spectrumFrames: List<List<Float>>, val pitchFramesHz: List<Float>, val beatTimesMs: List<Long>, val frameDurationMs: Long)

/** Offline PCM analysis. Decode/DSP run on IO; preview and export consume cached feature frames. */
object AudioSpectrumAnalyzer {
    private const val N = 1024
    private const val HOP = 512
    private const val BANDS = 32

    suspend fun analyze(context: Context, uriString: String, durationMs: Long): AudioAnalysisResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r") ?: return@withContext empty()
            extractor.setDataSource(descriptor.fileDescriptor)
            var source: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { extractor.selectTrack(i); source = f; break }
            }
            val inputFormat = source ?: return@withContext empty()
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext empty()
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0); decoder.start()

            val spectra = ArrayList<List<Float>>()
            val pitches = ArrayList<Float>()
            val bass = ArrayList<Float>()
            val window = FloatArray(N)
            var filled = 0
            var frameIndex = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val frameMs = max(1L, HOP * 1000L / rate)

            fun push(sample: Float) {
                window[filled++] = sample.coerceIn(-1f, 1f)
                if (filled == N) {
                    val levels = logBands(fft(window), rate)
                    spectra.add(levels.toList())
                    pitches.add(if (frameIndex++ % 4 == 0) estimatePitch(window, rate) else 0f)
                    bass.add(levels.take(6).average().toFloat())
                    System.arraycopy(window, HOP, window, 0, N - HOP)
                    filled = N - HOP
                }
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = runCatching { out.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        if (info.size > 0) decoder.getOutputBuffer(outIndex)?.let { buffer ->
                            val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            pcm.position(info.offset); pcm.limit(info.offset + info.size)
                            val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frameBytes = bytes * channels
                            while (pcm.remaining() >= frameBytes) {
                                var mono = 0f
                                repeat(channels) { mono += if (bytes == 4) pcm.float else pcm.short.toFloat() / 32768f }
                                push(mono / channels)
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
            if (spectra.isEmpty()) return@withContext empty()
            val beats = ArrayList<Long>()
            var baseline = 0.035f
            var lastBeat = -1000L
            for (i in bass.indices) {
                val flux = (bass[i] - bass.getOrElse(i - 1) { bass[i] }).coerceAtLeast(0f)
                baseline += (bass[i] - baseline) * 0.025f
                val t = i * frameMs
                if (flux > max(0.018f, baseline * 0.2f) && t - lastBeat >= 180L) { beats.add(t); lastBeat = t }
            }
            AudioAnalysisResult(spectra, pitches, beats, frameMs)
        } catch (_: Throwable) {
            empty()
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { extractor.release() }; runCatching { descriptor?.close() }
        }
    }

    fun bandsAt(frames: List<List<Float>>, timeMs: Long, frameMs: Long): FloatArray {
        if (frames.isEmpty()) return FloatArray(BANDS) { 0.04f }
        val frame = frames[(timeMs.coerceAtLeast(0L) / frameMs.coerceAtLeast(1L)).toInt().coerceIn(0, frames.lastIndex)]
        return FloatArray(BANDS) { i -> frame.getOrNull(i)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.04f }
    }

    private fun fft(source: FloatArray): FloatArray {
        val re = source.copyOf(); val im = FloatArray(N)
        for (i in 0 until N) re[i] *= (0.5 - 0.5 * cos(2.0 * PI * i / (N - 1))).toFloat()
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= N) {
            val angle = -2.0 * PI / len
            val wr0 = cos(angle); val wi0 = kotlin.math.sin(angle)
            var base = 0
            while (base < N) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val even = base + k; val odd = even + len / 2
                    val tr = wr * re[odd] - wi * im[odd]; val ti = wr * im[odd] + wi * re[odd]
                    re[odd] = (re[even] - tr).toFloat(); im[odd] = (im[even] - ti).toFloat()
                    re[even] = (re[even] + tr).toFloat(); im[even] = (im[even] + ti).toFloat()
                    val next = wr * wr0 - wi * wi0; wi = wr * wi0 + wi * wr0; wr = next
                }
                base += len
            }
            len = len shl 1
        }
        return FloatArray(N / 2) { i -> sqrt(re[i] * re[i] + im[i] * im[i]) }
    }

    private fun logBands(mag: FloatArray, rate: Int): FloatArray {
        val lowHz = 35f; val highHz = min(16_000f, rate / 2f).coerceAtLeast(36f)
        return FloatArray(BANDS) { b ->
            val low = lowHz * Math.pow((highHz / lowHz).toDouble(), b.toDouble() / BANDS).toFloat()
            val high = lowHz * Math.pow((highHz / lowHz).toDouble(), (b + 1).toDouble() / BANDS).toFloat()
            val first = (low * N / rate).toInt().coerceIn(1, mag.lastIndex)
            val last = (high * N / rate).toInt().coerceIn(first, mag.lastIndex)
            var sum = 0f
            for (i in first..last) sum += mag[i]
            (ln(1f + sum / (last - first + 1) * 18f) / ln(19f)).coerceIn(0f, 1f)
        }
    }

    private fun estimatePitch(samples: FloatArray, rate: Int): Float {
        val x = FloatArray(samples.size / 4) { samples[it * 4] }
        val sr = rate / 4
        var energy = 0f
        for (v in x) energy += v * v
        if (energy / x.size < 0.00002f) return 0f
        val minLag = (sr / 1000).coerceAtLeast(2); val maxLag = (sr / 65).coerceAtMost(x.size / 2)
        var bestLag = -1; var best = 0f
        for (lag in minLag..maxLag) {
            var cross = 0f; var aa = 0f; var bb = 0f
            for (i in 0 until x.size - lag step 2) { val a = x[i]; val b = x[i + lag]; cross += a * b; aa += a * a; bb += b * b }
            val score = cross / sqrt((aa * bb).coerceAtLeast(1e-12f))
            if (score > best) { best = score; bestLag = lag }
        }
        return if (bestLag > 0 && best > 0.55f) sr.toFloat() / bestLag else 0f
    }

    private fun empty() = AudioAnalysisResult(emptyList(), emptyList(), emptyList(), 23L)
}
