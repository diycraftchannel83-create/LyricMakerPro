package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import com.example.model.AudioBeatType
import com.example.model.AudioTrackConfig
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
                isLooping = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isCustomFile = false
        }
    }

    fun play() {
        if (_isPlaying.value) return
        val track = currentTrack ?: return

        _isPlaying.value = true
        playbackStartSystemTime = System.currentTimeMillis()
        playheadOffsetOnStart = _playheadMs.value

        if (track.beatType != AudioBeatType.CUSTOM_FILE || mediaPlayer == null) {
            _isPlaying.value = false
            return
        }
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

        // Decay spectrum bands gently
        scope.launch {
            _spectrumBands.value = FloatArray(16) { 0.05f }
            _bassEnergy.value = 0.05f
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
                val elapsed = System.currentTimeMillis() - playbackStartSystemTime
                var newPos = playheadOffsetOnStart + elapsed

                if (newPos >= track.durationMs) {
                    // Loop back to start
                    newPos = 0L
                    playheadOffsetOnStart = 0L
                    playbackStartSystemTime = System.currentTimeMillis()
                    if (isCustomFile) {
                        mediaPlayer?.seekTo(0)
                    }
                }

                _playheadMs.value = newPos
                updateSpectrumForPosition(newPos, track)

                delay(16) // ~60fps sync updates
            }
        }
    }

    private fun updateSpectrumForPosition(positionMs: Long, track: AudioTrackConfig) {
        val bpm = track.bpm.coerceAtLeast(60)
        val beatIntervalMs = (60_000.0 / bpm).toDouble()
        val currentBeat = (positionMs.toDouble() / beatIntervalMs)
        val beatFraction = (currentBeat % 1.0).toFloat()

        // Bass pulse peaks at the start of each quarter note beat
        val kickEnv = (1f - beatFraction * 2.5f).coerceIn(0f, 1f)
        val isSnareBeat = (currentBeat.toInt() % 2 == 1)
        val snareEnv = if (isSnareBeat) (1f - beatFraction * 3f).coerceIn(0f, 1f) else 0f
        val hiHatEnv = (1f - ((currentBeat * 4) % 1.0).toFloat() * 4f).coerceIn(0f, 0.7f)

        val bands = FloatArray(16)
        for (i in 0 until 16) {
            val freqRatio = i / 15f
            val baseLevel = when {
                i < 3 -> kickEnv * 0.95f + 0.15f // sub bass
                i in 3..7 -> snareEnv * 0.8f + kickEnv * 0.3f + 0.2f // mids
                else -> hiHatEnv * 0.7f + 0.1f // highs
            }
            // Add subtle undulating harmonics
            val wobble = sin(positionMs * 0.005 + i * 0.8).toFloat() * 0.12f
            bands[i] = (baseLevel + wobble).coerceIn(0.05f, 1.0f)
        }

        _spectrumBands.value = bands
        _bassEnergy.value = (kickEnv * 0.85f + 0.15f).coerceIn(0.1f, 1.5f)
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
