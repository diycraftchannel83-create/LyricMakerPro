package com.winsmediatama.lyricmakerpro.data

import com.winsmediatama.lyricmakerpro.model.*
import java.util.UUID

object PresetLibrary {

    val typographyPresets: List<TypographyPreset> = listOf(
        TypographyPreset(
            id = "preset_neon_glow",
            name = "Neon Glow",
            fontFamilyName = "SansSerif",
            fontSizeSp = 32f,
            primaryColorHex = "#FFFFFF",
            highlightColorHex = "#00F5D4",
            strokeColorHex = "#FF007F",
            strokeWidthDp = 2f,
            glowRadiusDp = 16f,
            shadowColorHex = "#FF007F",
            letterSpacingSp = 1.2f,
            isBold = true
        ),
        TypographyPreset(
            id = "preset_kinetic_pop",
            name = "Kinetic Pop",
            fontFamilyName = "SansSerif",
            fontSizeSp = 34f,
            primaryColorHex = "#FEE440",
            highlightColorHex = "#FFFFFF",
            strokeColorHex = "#000000",
            strokeWidthDp = 3f,
            glowRadiusDp = 8f,
            shadowColorHex = "#000000",
            letterSpacingSp = 1.5f,
            isBold = true
        ),
        TypographyPreset(
            id = "preset_cyberpunk",
            name = "Cyberpunk",
            fontFamilyName = "Monospace",
            fontSizeSp = 28f,
            primaryColorHex = "#00F5D4",
            highlightColorHex = "#FF007F",
            strokeColorHex = "#13151F",
            strokeWidthDp = 1.5f,
            glowRadiusDp = 12f,
            shadowColorHex = "#7B2CBF",
            letterSpacingSp = 2f,
            isBold = true
        ),
        TypographyPreset(
            id = "preset_typewriter",
            name = "Retro Typewriter",
            fontFamilyName = "Monospace",
            fontSizeSp = 26f,
            primaryColorHex = "#FFEAA7",
            highlightColorHex = "#55EFC4",
            strokeColorHex = "#2D3436",
            strokeWidthDp = 1f,
            glowRadiusDp = 4f,
            shadowColorHex = "#636E72",
            letterSpacingSp = 1.8f,
            isBold = false
        ),
        TypographyPreset(
            id = "preset_elegant_serif",
            name = "Cinema Serif",
            fontFamilyName = "Serif",
            fontSizeSp = 30f,
            primaryColorHex = "#F8F9FA",
            highlightColorHex = "#FDCB6E",
            strokeColorHex = "#2B2F44",
            strokeWidthDp = 1f,
            glowRadiusDp = 6f,
            shadowColorHex = "#80000000",
            letterSpacingSp = 2.5f,
            isBold = false,
            isItalic = true
        ),
        TypographyPreset(
            id = "preset_brutalist",
            name = "Heavy Impact",
            fontFamilyName = "SansSerif",
            fontSizeSp = 36f,
            primaryColorHex = "#FFFFFF",
            highlightColorHex = "#FF5964",
            strokeColorHex = "#000000",
            strokeWidthDp = 4f,
            glowRadiusDp = 0f,
            shadowColorHex = "#FF5964",
            letterSpacingSp = 0.5f,
            isBold = true
        )
    )

    fun createDefaultProject(): VideoProject {
        // A new app session always starts with a clean, empty project.
        // Sample songs remain available only when the user explicitly opens the template library.
        return VideoProject(
            title = "Untitled Project",
            artist = "",
            audioTrack = AudioTrackConfig(
                title = "No audio",
                artist = "",
                bpm = 120,
                durationMs = 0L,
                beatType = AudioBeatType.NONE,
                customUri = null,
                waveformPeaks = emptyList()
            ),
            lyrics = emptyList(),
            typography = typographyPresets.first()
        )
    }

    val sampleProjects: List<VideoProject> by lazy {
        listOf(
            createNeonNightsProject(),
            createLofiMidnightProject(),
            createCyberVelocityProject(),
            createAcousticSunsetProject()
        )
    }

    private fun generatePeaks(bpm: Int, length: Int = 80): List<Float> {
        val list = mutableListOf<Float>()
        for (i in 0 until length) {
            val beatPhase = (i % 8) / 8f
            val base = if (beatPhase < 0.25f) 0.85f else 0.35f
            val noise = ((i * 17 + bpm) % 23) / 70f
            list.add((base + noise).coerceIn(0.15f, 1.0f))
        }
        return list
    }

    private fun createNeonNightsProject(): VideoProject {
        val audio = AudioTrackConfig(
            id = "audio_synthwave",
            title = "Neon Dreams (Synthwave)",
            artist = "RetroWave Collective",
            bpm = 120,
            durationMs = 28_000L,
            beatType = AudioBeatType.SYNTH_SYNTHWAVE,
            waveformPeaks = generatePeaks(120)
        )

        val rawLyrics = listOf(
            Triple("Midnight city glowing in neon light", 1000L, 5000L),
            Triple("Synthesizer echoes burning through the night", 5200L, 9500L),
            Triple("Chasing shadows on an endless road", 9800L, 14000L),
            Triple("Lost inside the rhythm, let the beat explode", 14300L, 19000L),
            Triple("Never looking back, we're alive again", 19300L, 23500L),
            Triple("Electric hearts until the very end", 24000L, 27500L)
        )

        val lyrics = rawLyrics.map { (text, start, end) ->
            createLyricLineWithWords(text, start, end)
        }

        return VideoProject(
            id = "proj_neon_nights",
            title = "Neon Dreams",
            artist = "RetroWave Collective",
            canvasRatio = CanvasRatio.TIKTOK_REELS_9_16,
            audioTrack = audio,
            lyrics = lyrics,
            typography = typographyPresets[0], // Neon Glow
            transitionEffect = TextTransitionEffect.KARAOKE_SWEEP,
            visualizer = VisualizerType.BARS_SPECTRUM,
            visualizerColorHex = "#00F5D4",
            background = BackgroundConfig(
                type = BackgroundType.GRADIENT,
                primaryColorHex = "#090A14",
                secondaryColorHex = "#231138",
                accentColorHex = "#FF007F",
                darkOverlayOpacity = 0.4f
            ),
            watermarkText = "@LyricMaker",
            showWatermark = true
        )
    }

    private fun createLofiMidnightProject(): VideoProject {
        val audio = AudioTrackConfig(
            id = "audio_lofi",
            title = "Coffee & Raindrops",
            artist = "Midnight Beats",
            bpm = 80,
            durationMs = 26_000L,
            beatType = AudioBeatType.SYNTH_LOFI,
            waveformPeaks = generatePeaks(80)
        )

        val rawLyrics = listOf(
            Triple("Raindrops tapping softly on the glass", 1000L, 5500L),
            Triple("Quiet thoughts in coffee cups that pass", 6000L, 11000L),
            Triple("Warm golden lamp in a gentle room", 11500L, 16500L),
            Triple("Lo-fi memories drifting into bloom", 17000L, 22000L),
            Triple("Slow down the world, breathe in peace", 22500L, 25500L)
        )

        val lyrics = rawLyrics.map { (text, start, end) ->
            createLyricLineWithWords(text, start, end)
        }

        return VideoProject(
            id = "proj_lofi_midnight",
            title = "Coffee & Raindrops",
            artist = "Midnight Beats",
            canvasRatio = CanvasRatio.SQUARE_FEED_1_1,
            audioTrack = audio,
            lyrics = lyrics,
            typography = typographyPresets[3], // Retro Typewriter
            transitionEffect = TextTransitionEffect.TYPEWRITER_GLITCH,
            visualizer = VisualizerType.FLOATING_PARTICLES,
            visualizerColorHex = "#FEE440",
            background = BackgroundConfig(
                type = BackgroundType.GRADIENT,
                primaryColorHex = "#12141A",
                secondaryColorHex = "#1E232B",
                accentColorHex = "#FFEAA7",
                darkOverlayOpacity = 0.5f
            ),
            watermarkText = "@LyricMaker",
            showWatermark = false
        )
    }

    private fun createCyberVelocityProject(): VideoProject {
        val audio = AudioTrackConfig(
            id = "audio_cyberpunk",
            title = "Hyperdrive Overload",
            artist = "Sub-Bass 2099",
            bpm = 135,
            durationMs = 24_000L,
            beatType = AudioBeatType.SYNTH_CYBERPUNK,
            waveformPeaks = generatePeaks(135)
        )

        val rawLyrics = listOf(
            Triple("Velocity rising, systems online", 500L, 4200L),
            Triple("Neural network firing across space and time", 4500L, 8800L),
            Triple("Drop the 808 bass, shake the floor", 9200L, 13800L),
            Triple("Overclocked adrenaline, screaming for more", 14200L, 18500L),
            Triple("No limits, no speed, quantum shift", 19000L, 23500L)
        )

        val lyrics = rawLyrics.map { (text, start, end) ->
            createLyricLineWithWords(text, start, end)
        }

        return VideoProject(
            id = "proj_cyber_velocity",
            title = "Hyperdrive Overload",
            artist = "Sub-Bass 2099",
            canvasRatio = CanvasRatio.TIKTOK_REELS_9_16,
            audioTrack = audio,
            lyrics = lyrics,
            typography = typographyPresets[1], // Kinetic Pop
            transitionEffect = TextTransitionEffect.KINETIC_BOUNCE,
            visualizer = VisualizerType.BASS_PULSE,
            visualizerColorHex = "#FF007F",
            background = BackgroundConfig(
                type = BackgroundType.CYBER_GRID,
                primaryColorHex = "#08090F",
                secondaryColorHex = "#190826",
                accentColorHex = "#00F5D4",
                darkOverlayOpacity = 0.35f
            ),
            watermarkText = "@LyricMaker",
            showWatermark = true
        )
    }

    private fun createAcousticSunsetProject(): VideoProject {
        val audio = AudioTrackConfig(
            id = "audio_acoustic",
            title = "Sunset Whispers",
            artist = "Folk & Horizon",
            bpm = 105,
            durationMs = 25_000L,
            beatType = AudioBeatType.SYNTH_ACOUSTIC,
            waveformPeaks = generatePeaks(105)
        )

        val rawLyrics = listOf(
            Triple("Golden sun sinking slowly by the sea", 1000L, 5500L),
            Triple("Warm sea breeze carries your memory to me", 6000L, 11000L),
            Triple("Every guitar chord hums a melody of hope", 11500L, 16800L),
            Triple("Holding on together as the shadows grow", 17200L, 21500L),
            Triple("We'll meet where the dawn begins to glow", 22000L, 24500L)
        )

        val lyrics = rawLyrics.map { (text, start, end) ->
            createLyricLineWithWords(text, start, end)
        }

        return VideoProject(
            id = "proj_acoustic_sunset",
            title = "Sunset Whispers",
            artist = "Folk & Horizon",
            canvasRatio = CanvasRatio.YOUTUBE_16_9,
            audioTrack = audio,
            lyrics = lyrics,
            typography = typographyPresets[4], // Cinema Serif
            transitionEffect = TextTransitionEffect.SLIDE_FADE_UP,
            visualizer = VisualizerType.CIRCULAR_WAVE,
            visualizerColorHex = "#FDCB6E",
            background = BackgroundConfig(
                type = BackgroundType.GRADIENT,
                primaryColorHex = "#1C0D0A",
                secondaryColorHex = "#361614",
                accentColorHex = "#E17055",
                darkOverlayOpacity = 0.4f
            ),
            watermarkText = "@LyricMaker",
            showWatermark = true
        )
    }

    fun createLyricLineWithWords(text: String, startMs: Long, endMs: Long): LyricLine {
        val wordTokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (wordTokens.isEmpty()) {
            return LyricLine(text = text, startMs = startMs, endMs = endMs, words = emptyList())
        }
        val totalDuration = (endMs - startMs).coerceAtLeast(100L)
        val step = totalDuration / wordTokens.size
        val words = wordTokens.mapIndexed { index, token ->
            val wStart = startMs + index * step
            val wEnd = if (index == wordTokens.lastIndex) endMs else wStart + step
            LyricWord(text = token, startMs = wStart, endMs = wEnd)
        }
        return LyricLine(
            id = UUID.randomUUID().toString(),
            text = text,
            startMs = startMs,
            endMs = endMs,
            words = words
        )
    }

    /**
     * Parses LRC string (e.g., "[01:23.45] Lyric line") or plain text file.
     */
    fun parseLrcOrText(rawText: String, defaultStartOffsetMs: Long = 1000L, defaultLineDurationMs: Long = 3500L): List<LyricLine> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lrcRegex = Regex("""^\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")

        val parsedLrcEntries = mutableListOf<Pair<Long, String>>()

        for (line in lines) {
            val match = lrcRegex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val millisRaw = match.groupValues[3]
                val ms = when (millisRaw.length) {
                    1 -> millisRaw.toLong() * 100
                    2 -> millisRaw.toLong() * 10
                    3 -> millisRaw.toLong()
                    else -> 0L
                }
                val timestampMs = (min * 60 + sec) * 1000L + ms
                val text = match.groupValues[4].trim()
                if (text.isNotBlank()) {
                    parsedLrcEntries.add(timestampMs to text)
                }
            }
        }

        if (parsedLrcEntries.isNotEmpty()) {
            return parsedLrcEntries.mapIndexed { index, (timeMs, text) ->
                val nextTime = if (index < parsedLrcEntries.lastIndex) {
                    parsedLrcEntries[index + 1].first
                } else {
                    timeMs + defaultLineDurationMs
                }
                val endTime = nextTime.coerceAtLeast(timeMs + 500L)
                createLyricLineWithWords(text, timeMs, endTime)
            }
        }

        // Plain text fallback
        var currentMs = defaultStartOffsetMs
        return lines.map { text ->
            val start = currentMs
            val end = currentMs + defaultLineDurationMs
            currentMs = end + 300L
            createLyricLineWithWords(text, start, end)
        }
    }

    fun exportToLrc(lyrics: List<LyricLine>): String {
        val sb = StringBuilder()
        lyrics.sortedBy { it.startMs }.forEach { line ->
            val totalSec = line.startMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            val msHundredths = (line.startMs % 1000) / 10
            sb.append(String.format("[%02d:%02d.%02d] %s\n", min, sec, msHundredths, line.text))
        }
        return sb.toString()
    }
}
