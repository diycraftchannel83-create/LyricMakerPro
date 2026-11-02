package com.example.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import java.util.UUID

enum class CanvasRatio(
    val label: String,
    val iconDescription: String,
    val aspectRatio: Float, // width / height
    val exportWidth: Int,
    val exportHeight: Int
) {
    TIKTOK_REELS_9_16("9:16", "Reels & TikTok (Vertical)", 9f / 16f, 1080, 1920),
    SQUARE_FEED_1_1("1:1", "Instagram Post (Square)", 1f / 1f, 1080, 1080),
    YOUTUBE_16_9("16:9", "YouTube (Landscape)", 16f / 9f, 1920, 1080),
    INSTAGRAM_PORTRAIT_4_5("4:5", "Portrait Feed (4:5)", 4f / 5f, 1080, 1350)
}

enum class TextTransitionEffect(val label: String, val description: String) {
    KARAOKE_SWEEP("Karaoke Sweep", "Real-time color fill synchronized with song beat"),
    KINETIC_BOUNCE("Kinetic Pop", "High-energy scale bounce and overshoot punch"),
    NEON_PULSE("Neon Pulse", "Electric neon glow power-on flicker and shimmer"),
    TYPEWRITER_GLITCH("Typewriter Glitch", "Terminal letter-by-letter reveal with digital glitch"),
    SLIDE_FADE_UP("Float & Fade", "Smooth cinema glide entrance from bottom with soft fade"),
    WAVE_RIPPLE("Wave Rhythm", "Sinuous wavy displacement vibrating with bassline"),
    BURST_ZOOM("Drop Burst", "Explosive zoom impact on lyric cue")
}

enum class VisualizerType(val label: String, val description: String) {
    BARS_SPECTRUM("Spectrum Bars", "16-band gradient frequency spectrum equalizer"),
    CIRCULAR_WAVE("Circular Ring", "Radial glowing pulse rings vibrating with audio bass"),
    OSCILLOSCOPE("Oscilloscope", "Continuous smooth neon audio waveform wave"),
    FLOATING_PARTICLES("Ambient Dust", "Dreamy floating bokeh dust particles"),
    VINYL_SPINNER("Vinyl Disc", "Retro rotating vinyl record with groove reflections"),
    BASS_PULSE("Bass Shockwave", "Expanding concentric acoustic ripples on kick beat"),
    NONE("Off", "Clean background without visualizer overlay")
}

enum class BackgroundType {
    GRADIENT,
    MESH,
    SOLID,
    IMAGE,
    CYBER_GRID
}

data class BackgroundConfig(
    val type: BackgroundType = BackgroundType.GRADIENT,
    val primaryColorHex: String = "#0A0B10",
    val secondaryColorHex: String = "#1A1528",
    val accentColorHex: String = "#00F5D4",
    val imageUri: String? = null,
    val blurRadiusDp: Float = 0f,
    val darkOverlayOpacity: Float = 0.45f
)

enum class FontVisualEffect(val label: String) { NONE("Clean"), NEON_GLOW("Neon Glow"), SOFT_SHADOW("Soft Shadow"), DOUBLE_OUTLINE("Double Outline") }\n\nenum class LyricLayoutMode(val label: String) { SINGLE_LINE("Single Line"), KARAOKE_TWO_LINE("Two-Line Karaoke") }\n\ndata class TypographyPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val fontFamilyName: String = "SansSerif",
    val fontSizeSp: Float = 30f,
    val primaryColorHex: String = "#FFFFFF",
    val highlightColorHex: String = "#00F5D4",
    val strokeColorHex: String = "#000000",
    val strokeWidthDp: Float = 1.5f,
    val glowRadiusDp: Float = 8f,
    val shadowColorHex: String = "#40000000",
    val letterSpacingSp: Float = 1.0f,
    val isBold: Boolean = true,
    val isItalic: Boolean = false,
    val textAlign: TextAlign = TextAlign.Center,
    val positionXPercent: Float = 50f,
    val positionYPercent: Float = 56f
)

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricLine(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<LyricWord> = emptyList()
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(100L)
}

enum class AudioBeatType(val label: String, val bpm: Int, val description: String) {
    SYNTH_SYNTHWAVE("Synthwave Pulse", 120, "Punchy retro kick, snappy snare, 80s analog bass"),
    SYNTH_LOFI("Midnight Lo-Fi", 80, "Warm sub bass, vinyl crackle, gentle chill chords"),
    SYNTH_CYBERPUNK("Cyberpunk Beat", 135, "Deep 808 glide, crisp rolling hi-hats, laser arp"),
    SYNTH_ACOUSTIC("Acoustic Groove", 105, "Organic drum snaps, warm rhythm bounce"),
    CUSTOM_FILE("Custom Audio File", 120, "Imported sound from your device storage")
}

data class AudioTrackConfig(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val bpm: Int = 120,
    val durationMs: Long = 30_000L,
    val beatType: AudioBeatType = AudioBeatType.SYNTH_SYNTHWAVE,
    val customUri: String? = null,
    val waveformPeaks: List<Float> = emptyList()
)

data class VideoProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val canvasRatio: CanvasRatio = CanvasRatio.TIKTOK_REELS_9_16,
    val audioTrack: AudioTrackConfig,
    val lyrics: List<LyricLine>,
    val typography: TypographyPreset,
    val transitionEffect: TextTransitionEffect = TextTransitionEffect.KARAOKE_SWEEP,
    val visualizer: VisualizerType = VisualizerType.BARS_SPECTRUM,
    val visualizerColorHex: String = "#00F5D4",
    val background: BackgroundConfig = BackgroundConfig(),
    val watermarkText: String = "@LyricMaker",
    val showWatermark: Boolean = false,
    val playbackSpeed: Float = 1.0f
) {
    val durationMs: Long get() = audioTrack.durationMs
}

enum class ExportQuality(
    val label: String,
    val resolutionLabel: String,
    val scaleFactor: Float,
    val targetBitrate: Int
) {
    FULL_HD_1080P("1080p Full HD", "1080p • 60fps", 1.0f, 8_000_000),
    HD_720P("720p HD", "720p • 30fps", 0.666f, 4_000_000),
    SD_480P("480p Social Clip", "480p • Fast Export", 0.444f, 2_000_000)
}

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    MP4_VIDEO("MP4 Video", "mp4", "video/mp4"),
    ANIMATED_CLIP("Animated Status Clip", "mp4", "video/mp4"),
    FRAME_STILL("Cover Poster (PNG)", "png", "image/png")
}

fun parseColorHex(hex: String, defaultColor: Color = Color.White): Color {
    return try {
        val clean = hex.removePrefix("#")
        val parsed = clean.toLong(16)
        when (clean.length) {
            6 -> Color((0xFF000000 or parsed).toInt())
            8 -> Color(parsed.toInt())
            else -> defaultColor
        }
    } catch (_: Exception) {
        defaultColor
    }
}
