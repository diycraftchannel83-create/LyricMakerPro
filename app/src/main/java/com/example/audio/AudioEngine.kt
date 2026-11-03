package com.winsmediatama.lyricmakerpro.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import com.winsmediatama.lyricmakerpro.model.AudioBeatType
import com.winsmediatama.lyricmakerpro.model.AudioTrackConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

class AudioEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mediaPlayer: MediaPlayer? = null
    private var synthAudioTrack: AudioTrack? = null
    private var synthPlayJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playheadMs = MutableStateFlow(0L)
    val playheadMs = _playheadMs.asStateFlow()

    // 16-band normalized spectrum levels (0.0f .. 1.0f) for visualizer
    private val _spectrumBands = MutableStateFlow(FloatArray(16) { 0.1f })
    val spectrumBands = _spectrumBands.asStateFlow()

    private val _bassEnergy = MutableStateFlow(0.1f)
    val bassEnergy = _bassEnergy.asStateFlow()

    private var currentTrack: AudioTrackConfig? = null
    private var isCustomFile = false
    private var playbackStartSystemTime = 0L
    private var playheadOffsetOnStart = 0L
    private var tickerJob: Job? = null

    // Reused DSP state. Mutated only by the background ticker coroutine.
    private val smoothedBands = FloatArray(32) { 0.06f }
    private var smoothedBass = 0.06f
    private var previousEnvelope = 0f
    private var envelopeFloor = 0.04f
    private var lastBeatTimeMs = Long.MIN_VALUE

    fun loadTrack(track: AudioTrackConfig) {
        stop()
        currentTrack = track
        _playheadMs.value = 0L

        if (track.beatType == AudioBeatType.CUSTOM_FILE && track.customUri != null) {
            isCustomFile = true
            initCustomMediaPlayer(track.customUri)
        } else {
            isCustomFile = false
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    private fun initCustomMediaPlayer(uriString: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.parse(uriString))
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                prepare()
                isLooping = false
                setOnCompletionListener {
                    // Loop at the actual decoded media duration rather than stale project metadata.
                    if (_isPlaying.value) {
                        seekTo(0)
                        start()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isCustomFile = false
        }
    }

    fun play() {
        if (_isPlaying.value) return
        val track = currentTrack ?: return

        // Built-in beat types are visual/demo presets only. Real playback is
        // available only when the user imports an audio file.
        if (track.beatType != AudioBeatType.CUSTOM_FILE || mediaPlayer == null) {
            _isPlaying.value = false
            return
        }

        _isPlaying.value = true
        playbackStartSystemTime = System.currentTimeMillis()
        playheadOffsetOnStart = _playheadMs.value

        mediaPlayer?.seekTo(_playheadMs.value.toInt())
        mediaPlayer?.start()

        startTicker(track)
    }

    fun pause() {
        if (!_isPlaying.value) return
        _isPlaying.value = false

        if (isCustomFile) {
            mediaPlayer?.pause()
        } else {
            stopSynthTrack()
        }

        tickerJob?.cancel()
        tickerJob = null

        // Release smoothly rather than snapping the spectrum to silence.
        scope.launch {
            for (i in smoothedBands.indices) smoothedBands[i] = min(smoothedBands[i], 0.08f)
            smoothedBass = min(smoothedBass, 0.08f)
            _spectrumBands.value = smoothedBands.copyOf()
            _bassEnergy.value = smoothedBass
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val track = currentTrack ?: return
        val clamped = positionMs.coerceIn(0L, track.durationMs)
        _playheadMs.value = clamped
        playheadOffsetOnStart = clamped
        playbackStartSystemTime = System.currentTimeMillis()

        if (isCustomFile) {
            mediaPlayer?.seekTo(clamped.toInt())
        } else if (_isPlaying.value) {
            stopSynthTrack()
            startSynthesizedPlayback(track, clamped)
        }

        updateSpectrumForPosition(clamped, track)
    }

    fun stop() {
        pause()
        _playheadMs.value = 0L
        playheadOffsetOnStart = 0L
    }

    private fun startTicker(track: AudioTrackConfig) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _isPlaying.value) {
                // MediaPlayer is the playback clock. A wall-clock ticker drifts during
                // buffering, audio focus changes, and device load.
                val player = mediaPlayer
                val position = if (isCustomFile && player != null) {
                    runCatching { player.currentPosition.toLong() }.getOrDefault(_playheadMs.value)
                } else {
                    val elapsed = System.currentTimeMillis() - playbackStartSystemTime
                    (playheadOffsetOnStart + elapsed).coerceAtMost(track.durationMs)
                }
                _playheadMs.value = position.coerceAtLeast(0L)
                updateSpectrumForPosition(_playheadMs.value, track)
                delay(16) // Keep the audio feature clock close to a 60Hz visualizer frame cadence.
            }
        }
    }

    private fun updateSpectrumForPosition(positionMs: Long, track: AudioTrackConfig) {
        val bpm = track.bpm.coerceIn(40, 240)
        val beatIntervalMs = 60_000.0 / bpm
        val beatPosition = positionMs / beatIntervalMs
        val beatFraction = (beatPosition % 1.0).toFloat()
        val bpmKick = (1f - beatFraction * 3.2f).coerceIn(0f, 1f)

        // The cached waveform is an amplitude envelope, not a frequency spectrum.
        // Use it only for transient energy and keep BPM as a graceful fallback.
        val peaks = track.waveformPeaks
        val envelope = if (peaks.isNotEmpty() && track.durationMs > 0L) {
            val index = ((positionMs.toDouble() / track.durationMs) * peaks.size)
                .toInt().coerceIn(0, peaks.lastIndex)
            peaks[index].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        } else 0f

        val localBaseline = if (peaks.isNotEmpty() && track.durationMs > 0L) {
            val center = ((positionMs.toDouble() / track.durationMs) * peaks.size)
                .toInt().coerceIn(0, peaks.lastIndex)
            val radius = (peaks.size / (track.durationMs / 1000f).coerceAtLeast(1f) * 0.45f)
                .toInt().coerceIn(2, 24)
            var sum = 0f
            var count = 0
            for (offset in -radius..radius step 2) {
                val sample = peaks.getOrNull(center + offset)
                if (sample != null && sample.isFinite()) {
                    sum += sample.coerceIn(0f, 1f)
                    count++
                }
            }
            if (count > 0) sum / count else envelopeFloor
        } else envelopeFloor

        envelopeFloor += (localBaseline - envelopeFloor) * 0.035f
        val transient = if (envelope > max(0.08f, envelopeFloor * 1.42f) &&
            envelope - previousEnvelope > 0.025f &&
            (lastBeatTimeMs == Long.MIN_VALUE || positionMs - lastBeatTimeMs > 180L)
        ) {
            lastBeatTimeMs = positionMs
            ((envelope - envelopeFloor) / (1f - envelopeFloor).coerceAtLeast(0.08f)).coerceIn(0f, 1f)
        } else 0f
        previousEnvelope = envelope

        // This is a tempo/envelope-reactive fallback until decoded PCM FFT frames
        // are connected to the preview and export feature timeline.
        val kick = max(bpmKick * 0.72f, transient)
        val snare = if ((beatPosition.toInt() % 2) == 1) {
            (1f - beatFraction * 3.4f).coerceIn(0f, 1f) * 0.55f
        } else 0f
        val hat = (1f - ((beatPosition * 4.0) % 1.0).toFloat() * 4f).coerceIn(0f, 0.65f)

        val targets = FloatArray(32)
        for (i in targets.indices) {
            val normalized = i / 31f
            val base = when {
                i < 6 -> kick * (0.52f + (1f - normalized) * 0.42f) + envelope * 0.12f
                i < 22 -> snare * 0.58f + kick * 0.22f + envelope * 0.12f
                else -> hat * 0.72f + envelope * 0.08f
            }
            val pulse = sin(positionMs * (0.0035 + normalized * 0.004) + i * 0.47).toFloat() * 0.045f
            targets[i] = (base + pulse).coerceIn(0.025f, 1f)
        }

        // Fast attack, slower release: transient response stays punchy without jitter.
        for (i in smoothedBands.indices) {
            val target = targets[i]
            val coefficient = if (target > smoothedBands[i]) 0.62f else 0.16f
            smoothedBands[i] += (target - smoothedBands[i]) * coefficient
        }
        val bassTarget = max(kick, envelope * 0.72f).coerceIn(0.025f, 1f)
        val bassCoefficient = if (bassTarget > smoothedBass) 0.68f else 0.14f
        smoothedBass += (bassTarget - smoothedBass) * bassCoefficient

        _spectrumBands.value = smoothedBands.copyOf()
        _bassEnergy.value = smoothedBass
    }

    private fun startSynthesizedPlayback(track: AudioTrackConfig, startMs: Long) {
        stopSynthTrack()

        synthPlayJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 22050
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = max(minBufSize, 4096)

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            synthAudioTrack = audioTrack
            try {
                audioTrack.play()
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }

            val bpm = track.bpm.coerceIn(60, 180)
            val beatSamples = (sampleRate * 60.0 / bpm).toInt()
            var sampleIndex = (startMs * sampleRate / 1000).toLong()

            val shortBuffer = ShortArray(1024)

            while (isActive && _isPlaying.value) {
                for (i in shortBuffer.indices) {
                    val s = sampleIndex + i
                    val beatPos = (s % beatSamples).toInt()
                    val beatFraction = beatPos.toDouble() / beatSamples
                    val currentQuarterBeat = ((s / beatSamples) % 4).toInt()

                    var sample = 0.0

                    // 1. KICK (Beat 0, and on Beat 2 in cyberpunk)
                    val isKick = currentQuarterBeat == 0 || (track.beatType == AudioBeatType.SYNTH_CYBERPUNK && currentQuarterBeat == 2)
                    if (isKick && beatFraction < 0.25) {
                        val decay = 1.0 - (beatFraction / 0.25)
                        val pitch = 150.0 * decay + 45.0
                        sample += sin(2.0 * Math.PI * pitch * (beatPos.toDouble() / sampleRate)) * (decay * decay) * 0.65
                    }

                    // 2. SNARE / CLAP (Beats 1 & 3)
                    val isSnare = currentQuarterBeat == 1 || currentQuarterBeat == 3
                    if (isSnare && beatFraction < 0.22) {
                        val snareDecay = 1.0 - (beatFraction / 0.22)
                        val whiteNoise = (Math.random() * 2.0 - 1.0) * 0.35
                        val body = sin(2.0 * Math.PI * 220.0 * (beatPos.toDouble() / sampleRate)) * 0.25
                        sample += (whiteNoise + body) * snareDecay
                    }

                    // 3. HI-HATS (16th notes)
                    val sixteenthSamples = beatSamples / 4
                    val sixteenthFraction = (s % sixteenthSamples).toDouble() / sixteenthSamples
                    if (sixteenthFraction < 0.12) {
                        val hatDecay = 1.0 - (sixteenthFraction / 0.12)
                        sample += (Math.random() * 2.0 - 1.0) * hatDecay * 0.12
                    }

                    // 4. MELODIC SYNTH / BASS ACCORDING TO BEAT TYPE
                    val chordIndex = ((s / (beatSamples * 4)) % 4).toInt()
                    val baseFreq = when (track.beatType) {
                        AudioBeatType.SYNTH_SYNTHWAVE -> when (chordIndex) {
                            0 -> 110.0 // A2
                            1 -> 87.3  // F2
                            2 -> 130.8 // C3
                            else -> 98.0 // G2
                        }
                        AudioBeatType.SYNTH_LOFI -> when (chordIndex) {
                            0 -> 130.8 // C3
                            1 -> 146.8 // D3
                            2 -> 116.5 // Bb2
                            else -> 103.8 // Ab2
                        }
                        AudioBeatType.SYNTH_CYBERPUNK -> when (chordIndex) {
                            0 -> 55.0  // A1 heavy sub
                            1 -> 65.4  // C2
                            2 -> 73.4  // D2
                            else -> 49.0  // G1
                        }
                        else -> 110.0
                    }

                    // Warm saw/triangle bass
                    val t = s.toDouble() / sampleRate
                    val bassWave = sin(2.0 * Math.PI * baseFreq * t) + 0.3 * sin(2.0 * Math.PI * baseFreq * 2.0 * t)
                    sample += bassWave * 0.18

                    shortBuffer[i] = (sample.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
                }

                sampleIndex += shortBuffer.size
                audioTrack.write(shortBuffer, 0, shortBuffer.size)
            }
        }
    }

    private fun stopSynthTrack() {
        synthPlayJob?.cancel()
        synthPlayJob = null
        try {
            synthAudioTrack?.pause()
            synthAudioTrack?.flush()
            synthAudioTrack?.release()
        } catch (_: Exception) {}
        synthAudioTrack = null
    }

    fun release() {
        stop()
        mediaPlayer?.release()
        mediaPlayer = null
        scope.cancel()
    }
}
