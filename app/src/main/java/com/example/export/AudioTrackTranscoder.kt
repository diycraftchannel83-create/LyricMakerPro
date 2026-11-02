package com.example.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes the imported source (MP3, WAV, AAC, etc.) and re-encodes it as AAC-LC.
 * The resulting M4A is suitable for MP4 muxing; source audio is never silently omitted.
 */
internal object AudioTrackTranscoder {
    fun transcodeToAac(context: Context, sourceUri: String, output: File, durationMs: Long) {
        val extractor = MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioTrack = -1
        var decoderInputEos = false
        var encoderInputEos = false
        var encoderOutputEos = false
        var encoderReady = false
        var sampleRate = 0
        var channels = 0
        var lastPtsUs = 0L

        try {
            descriptor = context.contentResolver.openFileDescriptor(Uri.parse(sourceUri), "r")
                ?: error("Cannot open imported audio")
            extractor.setDataSource(descriptor.fileDescriptor)
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    sourceFormat = f
                    break
                }
            }
            val inputFormat = sourceFormat ?: error("Imported file has no audio track")
            val sourceMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var decoderOutputEos = false
            var outputFormatSeen = false

            while (!encoderOutputEos) {
                if (!decoderInputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)!!
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts >= durationMs * 1000L) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, maxOf(0L, durationMs * 1000L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, pts, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                val decoderIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (decoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat
                    sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                    val outFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
                    encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start()
                    encoderReady = true
                    outputFormatSeen = true
                } else if (decoderIndex >= 0) {
                    val pcm = decoder.getOutputBuffer(decoderIndex)
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (pcm != null && info.size > 0 && encoderReady && !encoderInputEos) {
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        val encInputIndex = encoder!!.dequeueInputBuffer(10_000)
                        if (encInputIndex >= 0) {
                            val encInput = encoder!!.getInputBuffer(encInputIndex)!!
                            encInput.clear()
                            val bytes = minOf(pcm.remaining(), encInput.remaining())
                            val oldLimit = pcm.limit()
                            pcm.limit(pcm.position() + bytes)
                            encInput.put(pcm)
                            pcm.limit(oldLimit)
                            val pts = maxOf(lastPtsUs, info.presentationTimeUs)
                            lastPtsUs = pts + (bytes.toLong() * 1_000_000L / (sampleRate * channels * 2L)).coerceAtLeast(1L)
                            encoder!!.queueInputBuffer(encInputIndex, 0, bytes, pts, 0)
                        }
                    }
                    if (eos && encoderReady && !encoderInputEos) {
                        val encInputIndex = encoder!!.dequeueInputBuffer(10_000)
                        if (encInputIndex >= 0) {
                            encoder!!.queueInputBuffer(encInputIndex, 0, 0, durationMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputEos = true
                        }
                    }
                    decoder.releaseOutputBuffer(decoderIndex, false)
                    decoderOutputEos = eos
                }

                if (encoderReady) {
                    while (true) {
                        val encIndex = encoder!!.dequeueOutputBuffer(info, 0)
                        if (encIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerStarted) error("AAC encoder changed output format twice")
                            audioTrack = muxer!!.addTrack(encoder!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        } else if (encIndex >= 0) {
                            val data = encoder!!.getOutputBuffer(encIndex)
                            if (data != null && info.size > 0 && muxerStarted &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer!!.writeSampleData(audioTrack, data, info)
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderOutputEos = true
                            encoder!!.releaseOutputBuffer(encIndex, false)
                        }
                    }
                }
                if (decoderOutputEos && !encoderInputEos && encoderReady) {
                    val encInputIndex = encoder!!.dequeueInputBuffer(10_000)
                    if (encInputIndex >= 0) {
                        encoder!!.queueInputBuffer(encInputIndex, 0, 0, durationMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderInputEos = true
                    }
                }
            }
            check(outputFormatSeen && muxerStarted) { "AAC audio encoding produced no output" }
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }; runCatching { muxer?.release() }
            runCatching { extractor.release() }; runCatching { descriptor?.close() }
        }
    }
}
