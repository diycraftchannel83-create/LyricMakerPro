package com.winsmediatama.lyricmakerpro.export

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

    /**
     * Synthesizes the procedural drum machine & melodic bassline into an AAC M4A track
     * so projects with built-in beats export immediately with high-fidelity synchronized audio.
     */
    fun synthesizeBeatToAac(track: com.winsmediatama.lyricmakerpro.model.AudioTrackConfig, output: File, durationMs: Long) {
        val sampleRate = 44100
        val channels = 1
        val bpm = track.bpm.coerceIn(60, 180)
        val beatSamples = (sampleRate * 60.0 / bpm).toInt().coerceAtLeast(1)
        val totalSamples = (durationMs * sampleRate / 1000L).coerceAtLeast(sampleRate.toLong())

        val outFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioTrack = -1

        try {
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()

            var sampleCursor = 0L
            val chunkSize = 2048
            var inputEos = false
            var outputEos = false

            while (!outputEos) {
                if (!inputEos) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (sampleCursor >= totalSamples) {
                            encoder.queueInputBuffer(inIdx, 0, 0, durationMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val count = minOf(chunkSize.toLong(), totalSamples - sampleCursor).toInt()
                            val ptsUs = (sampleCursor * 1_000_000L / sampleRate)

                            for (i in 0 until count) {
                                val s = sampleCursor + i
                                val beatPos = (s % beatSamples).toInt()
                                val beatFraction = beatPos.toDouble() / beatSamples
                                val currentQuarterBeat = ((s / beatSamples) % 4).toInt()
                                var sample = 0.0

                                // 1. Kick (Punch on 0, and 2 in cyberpunk)
                                val isKick = currentQuarterBeat == 0 || (track.beatType == com.winsmediatama.lyricmakerpro.model.AudioBeatType.SYNTH_CYBERPUNK && currentQuarterBeat == 2)
                                if (isKick && beatFraction < 0.28) {
                                    val decay = 1.0 - (beatFraction / 0.28)
                                    val pitch = 160.0 * decay + 42.0
                                    sample += kotlin.math.sin(2.0 * kotlin.math.PI * pitch * (beatPos.toDouble() / sampleRate)) * (decay * decay) * 0.72
                                }

                                // 2. Snare / Clap on 1 & 3
                                val isSnare = currentQuarterBeat == 1 || currentQuarterBeat == 3
                                if (isSnare && beatFraction < 0.24) {
                                    val snareDecay = 1.0 - (beatFraction / 0.24)
                                    val whiteNoise = (Math.random() * 2.0 - 1.0) * 0.38
                                    val body = kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * (beatPos.toDouble() / sampleRate)) * 0.26
                                    sample += (whiteNoise + body) * snareDecay
                                }

                                // 3. Hi-Hats (16th notes)
                                val sixteenthSamples = maxOf(1, beatSamples / 4)
                                val sixteenthFraction = (s % sixteenthSamples).toDouble() / sixteenthSamples
                                if (sixteenthFraction < 0.12) {
                                    val hatDecay = 1.0 - (sixteenthFraction / 0.12)
                                    sample += (Math.random() * 2.0 - 1.0) * hatDecay * 0.15
                                }

                                // 4. Melodic Bass / Chords
                                val chordIndex = ((s / (beatSamples * 4)) % 4).toInt()
                                val baseFreq = when (track.beatType) {
                                    com.winsmediatama.lyricmakerpro.model.AudioBeatType.SYNTH_SYNTHWAVE -> when (chordIndex) {
                                        0 -> 110.0 // A2
                                        1 -> 87.3  // F2
                                        2 -> 130.8 // C3
                                        else -> 98.0 // G2
                                    }
                                    com.winsmediatama.lyricmakerpro.model.AudioBeatType.SYNTH_LOFI -> when (chordIndex) {
                                        0 -> 130.8 // C3
                                        1 -> 146.8 // D3
                                        2 -> 116.5 // Bb2
                                        else -> 103.8 // Ab2
                                    }
                                    com.winsmediatama.lyricmakerpro.model.AudioBeatType.SYNTH_CYBERPUNK -> when (chordIndex) {
                                        0 -> 55.0  // A1 heavy sub
                                        1 -> 65.4  // C2
                                        2 -> 73.4  // D2
                                        else -> 49.0  // G1
                                    }
                                    else -> 110.0
                                }
                                val t = s.toDouble() / sampleRate
                                val bassWave = kotlin.math.sin(2.0 * kotlin.math.PI * baseFreq * t) + 0.3 * kotlin.math.sin(2.0 * kotlin.math.PI * baseFreq * 2.0 * t)
                                sample += bassWave * 0.22

                                val pcmShort = (sample.coerceIn(-1.0, 1.0) * 31000).toInt().toShort()
                                inBuf.putShort(pcmShort)
                            }
                            sampleCursor += count
                            encoder.queueInputBuffer(inIdx, 0, count * 2, ptsUs, 0)
                        }
                    }
                }

                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(info, 0)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) error("Encoder changed output format twice")
                        audioTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val data = encoder.getOutputBuffer(outIdx)
                        if (data != null && info.size > 0 && muxerStarted &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(audioTrack, data, info)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                        encoder.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
        } finally {
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }; runCatching { muxer?.release() }
        }
    }
}
