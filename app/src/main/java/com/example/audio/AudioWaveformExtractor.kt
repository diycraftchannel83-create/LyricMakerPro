package com.example.audio

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
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes the selected audio source to PCM and reduces it to normalized peak buckets
 * suitable for drawing a compact, real waveform in the editor timeline.
 */
object AudioWaveformExtractor {
    suspend fun extract(
        context: Context,
        uriString: String,
        durationMs: Long,
        peakCount: Int = 640
    ): List<Float> = withContext(Dispatchers.IO) {
        val peaks = FloatArray(peakCount.coerceIn(128, 1200))
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var sourceDescriptor: ParcelFileDescriptor? = null

        try {
            sourceDescriptor = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                ?: return@withContext emptyList<Float>()
            extractor.setDataSource(sourceDescriptor.fileDescriptor)
            var audioFormat: MediaFormat? = null
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(trackIndex)
                    audioFormat = format
                    break
                }
            }

            val inputFormat = audioFormat ?: return@withContext emptyList<Float>()
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext emptyList<Float>()
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val sourceDurationMs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else 0L
            val actualDurationMs = durationMs.takeIf { it > 0L } ?: sourceDurationMs
            val estimatedFrames = (actualDurationMs.coerceAtLeast(1L) * sampleRate / 1000L).coerceAtLeast(1L)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameIndex = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        pcmEncoding = runCatching {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val pcm = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val frameBytes = bytesPerSample * channelCount

                                while (pcm.remaining() >= frameBytes) {
                                    var framePeak = 0f
                                    repeat(channelCount) {
                                        val value = if (bytesPerSample == 4) {
                                            pcm.float
                                        } else {
                                            pcm.short.toFloat() / 32768f
                                        }
                                        framePeak = max(framePeak, abs(value))
                                    }
                                    val bucket = ((frameIndex.toDouble() / estimatedFrames) * peaks.size)
                                        .toInt().coerceIn(0, peaks.lastIndex)
                                    peaks[bucket] = max(peaks[bucket], framePeak.coerceIn(0f, 1f))
                                    frameIndex++
                                }
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val maximum = peaks.maxOrNull() ?: 0f
            if (maximum <= 0.0001f) emptyList()
            else peaks.map { (it / maximum).coerceIn(0f, 1f) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            runCatching { sourceDescriptor?.close() }
        }
    }
}
