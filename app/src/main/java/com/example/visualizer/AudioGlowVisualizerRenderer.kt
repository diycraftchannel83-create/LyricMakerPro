package com.winsmediatama.lyricmakerpro.visualizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.winsmediatama.lyricmakerpro.model.VisualizerType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance, zero-allocation visualizer rendering engine.
 * Emulates professional audio players (Avee Player, Traktor, After Effects audio spectrum)
 * with accurate bass kick punch, crisp treble shimmer, and gravity peak caps.
 *
 * All Paint, Path, and RectF instances are cached statically to avoid Garbage Collection
 * stutter during 60 FPS playback and high-speed MP4 export.
 */
object AudioGlowVisualizerRenderer {

    // 16-color pro-audio rainbow palette
    private val rainbowColors = intArrayOf(
        Color.rgb(0, 245, 212),   // Neon Cyan (Sub-bass)
        Color.rgb(0, 200, 255),   // Sky Blue
        Color.rgb(65, 105, 255),  // Royal Blue
        Color.rgb(120, 80, 255),  // Electric Violet
        Color.rgb(175, 45, 255),  // Deep Purple
        Color.rgb(238, 25, 180),  // Hot Magenta
        Color.rgb(255, 0, 128),   // Neon Crimson
        Color.rgb(255, 50, 60),   // Coral Red
        Color.rgb(255, 95, 30),   // Tangerine
        Color.rgb(255, 155, 20),  // Amber Orange
        Color.rgb(255, 210, 30),  // Gold Yellow
        Color.rgb(235, 255, 40),  // Lime Gold
        Color.rgb(160, 255, 45),  // Electric Lime
        Color.rgb(80, 255, 80),   // Spring Green
        Color.rgb(45, 245, 170),  // Mint Green
        Color.rgb(0, 245, 212)    // Neon Cyan (Treble)
    )

    private val coolBarColors = intArrayOf(
        Color.rgb(255, 170, 0), Color.rgb(255, 55, 35), Color.rgb(255, 0, 145),
        Color.rgb(185, 45, 255), Color.rgb(45, 110, 255), Color.rgb(0, 220, 255),
        Color.rgb(0, 245, 210), Color.rgb(255, 190, 0)
    )

    private val sinColors = intArrayOf(
        Color.rgb(0, 245, 212),
        Color.rgb(0, 150, 255),
        Color.rgb(90, 95, 255),
        Color.rgb(195, 50, 255),
        Color.rgb(255, 0, 140),
        Color.rgb(0, 230, 255),
        Color.rgb(50, 255, 180)
    )

    // Cached reusable Paints (Zero per-frame allocations)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val coreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wideStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 215, 235)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // Reusable Path and RectF objects
    private val cachedPath = Path()
    private val cachedReflectPath = Path()
    private val cachedRect = RectF()

    // Peak-hold ballistic tracker for pro-audio floating caps
    private const val MAX_BANDS = 32
    private val peakLevels = FloatArray(MAX_BANDS)
    private val peakHoldCounters = IntArray(MAX_BANDS)
    private var lastTimeMs = 0L

    /**
     * Resets or updates peak hold ballistics.
     * Peak caps hover at new heights, hold for 12 frames (~200ms), and then accelerate downward with gravity.
     */
    private fun updatePeakCap(index: Int, currentLevel: Float): Float {
        val i = index.coerceIn(0, MAX_BANDS - 1)
        if (currentLevel >= peakLevels[i]) {
            peakLevels[i] = currentLevel
            peakHoldCounters[i] = 12 // Hold for ~200ms at 60fps
        } else {
            if (peakHoldCounters[i] > 0) {
                peakHoldCounters[i]--
            } else {
                peakLevels[i] = max(0f, peakLevels[i] - 0.022f)
            }
        }
        return peakLevels[i]
    }

    /**
     * Draws Avee Player / Winamp style classic rainbow spectrum bars.
     * Accurately tracks bass kicks on the left and treble sparkle on the right.
     */
    fun drawClassicSpectrum(
        canvas: Canvas,
        width: Int,
        height: Int,
        timeMs: Long,
        bands: FloatArray,
        artist: String,
        trackTitle: String,
        exportPerformanceMode: Boolean = false
    ) {
        if (width <= 0 || height <= 0) return

        val minSide = min(width, height).toFloat()
        val baseY = height * 0.68f
        val count = 20
        val gap = width * 0.008f
        val totalWidth = width * 0.88f
        val barWidth = (totalWidth - gap * (count - 1)) / count
        val maxBarHeight = height * 0.28f

        // 1. Artist & Song Metadata
        val centerX = width * 0.5f
        if (artist.isNotBlank() && !artist.equals("unknown", true)) {
            titlePaint.textSize = height * 0.052f
            canvas.drawText(artist.trim(), centerX, height * 0.25f, titlePaint)
        }
        if (trackTitle.isNotBlank() && !trackTitle.equals("unknown", true)) {
            subtitlePaint.textSize = height * 0.034f
            canvas.drawText(trackTitle.trim(), centerX, height * 0.295f, subtitlePaint)
        }

        // 2. Treble-reactive floating sparkle particles
        val trebleSum = if (bands.size >= 8) bands.takeLast(8).maxOrNull() ?: 0.1f else 0.1f
        val particleCount = if (exportPerformanceMode) 28 else 64
        for (i in 0 until particleCount) {
            val seed = i * 117.3
            val x = ((sin(seed * 0.73) * 0.5 + 0.5) * width).toFloat()
            val speed = 0.0018 + (i % 5) * 0.0006
            val yOffset = ((timeMs * speed + seed) % 1.0).toFloat()
            val y = baseY - yOffset * height * 0.42f
            val tint = rainbowColors[i % rainbowColors.size]
            val r = minSide * (0.0018f + (i % 3) * 0.0012f) * (1f + trebleSum * 0.8f)
            val alpha = ((1f - yOffset) * 210 * (0.4f + trebleSum * 0.6f)).toInt().coerceIn(0, 240)
            particlePaint.color = withAlpha(tint, alpha)
            canvas.drawCircle(x, y, r, particlePaint)
        }

        // 3. Bass Kick Shockwave Floor Line
        val bassKick = if (bands.isNotEmpty()) bands.take(5).maxOrNull() ?: 0.08f else 0.08f
        val floorGlowAlpha = (40 + bassKick * 170).toInt().coerceIn(40, 255)
        baselinePaint.color = withAlpha(Color.rgb(0, 245, 212), floorGlowAlpha)
        baselinePaint.strokeWidth = max(2f, minSide * (0.004f + bassKick * 0.005f))
        canvas.drawLine(width * 0.04f, baseY, width * 0.96f, baseY, baselinePaint)

        // 4. Spectrum Bars & Floating Peak Caps
        val startX = (width - totalWidth) / 2f
        for (i in 0 until count) {
            // Map 20 visible bars to the 32 input logarithmic bands
            val bandIdx = (i * (bands.size - 1) / (count - 1)).coerceIn(0, bands.lastIndex)
            val rawEnergy = bands.getOrElse(bandIdx) { 0.1f }.coerceIn(0.04f, 1f)

            // Dynamic bounce with subtle micro-phase shimmer
            val energy = (rawEnergy * (0.92f + 0.08f * sin(timeMs * 0.005 + i * 0.6).toFloat())).coerceIn(0.04f, 1f)
            val barH = (energy * maxBarHeight).coerceAtLeast(minSide * 0.006f)
            val x = startX + i * (barWidth + gap)
            val tint = rainbowColors[(i * rainbowColors.size / count).coerceIn(0, rainbowColors.lastIndex)]

            // Bar Body (Upwards from baseline)
            cachedRect.set(x, baseY - barH, x + barWidth, baseY)
            val cornerR = barWidth * 0.28f

            // Halo glow pass
            haloPaint.color = withAlpha(tint, 75 + (energy * 65).toInt())
            canvas.drawRoundRect(cachedRect, cornerR, cornerR, haloPaint)

            // Core solid pass
            barPaint.color = withAlpha(tint, 225)
            canvas.drawRoundRect(cachedRect, cornerR, cornerR, barPaint)

            // Floating Peak Cap (Iconic Avee Player ballistics)
            val peakEnergy = updatePeakCap(i, energy)
            val peakY = baseY - (peakEnergy * maxBarHeight) - minSide * 0.008f
            cachedRect.set(x - barWidth * 0.05f, peakY - minSide * 0.0022f, x + barWidth * 1.05f, peakY + minSide * 0.0022f)
            capPaint.color = withAlpha(Color.WHITE, (180 + peakEnergy * 75).toInt().coerceIn(180, 255))
            canvas.drawRoundRect(cachedRect, cornerR, cornerR, capPaint)

            // Mirrored Floor Reflection (Soft fade-out downwards)
            val reflectH = barH * 0.42f
            cachedRect.set(x, baseY + minSide * 0.004f, x + barWidth, baseY + minSide * 0.004f + reflectH)
            barPaint.color = withAlpha(tint, (65 * (1f - energy * 0.2f)).toInt().coerceIn(20, 80))
            canvas.drawRoundRect(cachedRect, cornerR, cornerR, barPaint)
        }
    }

    /** Cool Bars: Dual-sided symmetrical neon equalizer with central waveform line. */
    fun drawCoolBars(canvas: Canvas, width: Int, height: Int, timeMs: Long, bands: FloatArray) {
        if (width <= 0 || height <= 0) return
        val centerY = height * 0.58f
        val minSide = min(width, height).toFloat()
        val count = 48
        val step = width.toFloat() / count
        val barWidth = (step * 0.42f).coerceAtLeast(1.5f)

        cachedPath.reset()
        cachedReflectPath.reset()

        wideStrokePaint.strokeCap = Paint.Cap.ROUND
        coreStrokePaint.strokeCap = Paint.Cap.SQUARE

        for (i in 0 until count) {
            val bandIdx = (i * (bands.size - 1) / (count - 1)).coerceIn(0, bands.lastIndex)
            val raw = bands.getOrElse(bandIdx) { 0.1f }.coerceIn(0.04f, 1f)
            val energy = (raw * (0.92f + 0.08f * sin(timeMs * 0.004 + i * 0.65).toFloat())).coerceIn(0.03f, 1f)
            val h = height * (0.02f + energy * 0.24f)
            val x = (i + 0.5f) * step
            val color = coolBarColors[(i * coolBarColors.size / count).coerceIn(0, coolBarColors.lastIndex)]

            // Soft glow
            wideStrokePaint.color = withAlpha(color, 85)
            wideStrokePaint.strokeWidth = step * 0.65f
            canvas.drawLine(x, centerY - h, x, centerY + h * 0.25f, wideStrokePaint)

            // Core bar
            coreStrokePaint.color = withAlpha(color, 240)
            coreStrokePaint.strokeWidth = barWidth
            canvas.drawLine(x, centerY - h, x, centerY, coreStrokePaint)

            // Mirrored reflection
            coreStrokePaint.color = withAlpha(color, (70 + energy * 70).toInt())
            coreStrokePaint.strokeWidth = barWidth * 0.7f
            canvas.drawLine(x, centerY + 2f, x, centerY + h * 0.45f, coreStrokePaint)

            val y = centerY - h * 0.08f + sin(timeMs * 0.002 + i * 0.35).toFloat() * height * 0.008f
            if (i == 0) {
                cachedPath.moveTo(x, y)
                cachedReflectPath.moveTo(x, centerY + (centerY - y) * 0.3f)
            } else {
                cachedPath.lineTo(x, y)
                cachedReflectPath.lineTo(x, centerY + (centerY - y) * 0.3f)
            }
        }

        // Center connecting neon wave line
        coreStrokePaint.color = withAlpha(Color.rgb(0, 245, 212), 220)
        coreStrokePaint.strokeWidth = max(1.5f, minSide * 0.0025f)
        canvas.drawPath(cachedPath, coreStrokePaint)

        coreStrokePaint.color = withAlpha(Color.rgb(0, 180, 255), 95)
        coreStrokePaint.strokeWidth = max(1f, minSide * 0.0015f)
        canvas.drawPath(cachedReflectPath, coreStrokePaint)
    }

    /** Sin Bars: Transparent multicolor sine-wave ribbons with audio-reactive harmonics. */
    fun drawSinBars(
        canvas: Canvas,
        width: Int,
        height: Int,
        timeMs: Long,
        bands: FloatArray,
        exportPerformanceMode: Boolean = false
    ) {
        if (width <= 0 || height <= 0) return
        val minSide = min(width, height).toFloat()
        val centerY = height * 0.52f
        val count = if (exportPerformanceMode) 64 else 96
        val stepX = width.toFloat() / (count - 1)

        fun energy(i: Int): Float {
            val idx = (i * (bands.size - 1) / (count - 1)).coerceIn(0, bands.lastIndex)
            return bands.getOrElse(idx) { 0.15f }.coerceIn(0.04f, 1f)
        }

        for (lane in -3..3) {
            cachedPath.reset()
            for (i in 0 until count) {
                val x = i * stepX
                val xNorm = i.toDouble() / (count - 1)
                val env = (0.15 + 0.85 * (0.5 + 0.5 * sin(xNorm * PI * 2.0 + 0.35))).toFloat()
                val phase = xNorm * 7.0 * PI + timeMs * 0.002 + lane * 0.28
                val amp = height * (0.015f + energy(i) * 0.28f) * env
                val y = centerY + sin(phase).toFloat() * amp + sin(phase * 2.2 + lane * 0.2).toFloat() * amp * 0.15f

                if (i == 0) cachedPath.moveTo(x, y) else cachedPath.lineTo(x, y)
            }
            val tint = sinColors[(lane + 3).coerceIn(0, sinColors.lastIndex)]

            // Glow halo pass
            wideStrokePaint.color = withAlpha(tint, 45)
            wideStrokePaint.strokeWidth = minSide * 0.014f
            canvas.drawPath(cachedPath, wideStrokePaint)

            // Sharp core ribbon
            coreStrokePaint.color = withAlpha(tint, 220 - kotlin.math.abs(lane) * 25)
            coreStrokePaint.strokeWidth = max(1.2f, minSide * 0.003f)
            canvas.drawPath(cachedPath, coreStrokePaint)
        }
    }

    /** Terrain 3D: Wireframe perspective mountain ridges reacting dynamically to bass and mids. */
    fun drawTerrainBars(canvas: Canvas, width: Int, height: Int, timeMs: Long, bands: FloatArray) {
        if (width <= 0 || height <= 0) return
        val minSide = min(width, height).toFloat()
        val columns = 64
        val rows = 8
        val horizon = height * 0.44f
        val stepX = width.toFloat() / (columns - 1)

        fun energy(col: Int): Float {
            val idx = (col * (bands.size - 1) / (columns - 1)).coerceIn(0, bands.lastIndex)
            return bands.getOrElse(idx) { 0.12f }.coerceIn(0.03f, 1f)
        }

        fun point(col: Int, row: Int): Pair<Float, Float> {
            val xNorm = col.toFloat() / (columns - 1)
            val depth = row.toFloat() / (rows - 1)
            val x = col * stepX
            val band = energy(col)
            val travel = timeMs * 0.00045 + row * 0.35
            val ridge = kotlin.math.abs(sin(xNorm * PI * 2.5 + travel * 0.3)).toFloat()
            val amplitude = height * (0.015f + band * 0.18f) * (0.35f + depth * 0.95f)
            val wave = ridge * amplitude + sin(xNorm * PI * 6.0 + travel).toFloat() * height * (0.006f + band * 0.02f)
            val y = horizon + depth * height * 0.12f - wave
            return x to y
        }

        for (row in 0 until rows) {
            cachedPath.reset()
            for (col in 0 until columns) {
                val (x, y) = point(col, row)
                if (col == 0) cachedPath.moveTo(x, y) else cachedPath.lineTo(x, y)
            }
            val tint = sinColors[(row * 2) % sinColors.size]
            val alpha = (60 + row * 18).coerceIn(60, 220)

            coreStrokePaint.color = withAlpha(tint, alpha)
            coreStrokePaint.strokeWidth = max(1f, minSide * (0.0028f - row * 0.00015f))
            canvas.drawPath(cachedPath, coreStrokePaint)
        }
    }

    /** Unified dispatcher for specialized glow/ambient visualizers. */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        type: VisualizerType,
        colorHex: String,
        timeMs: Long,
        spectrumBands: FloatArray,
        bassEnergy: Float,
        previewQuality: Boolean = false
    ) {
        if (width <= 0 || height <= 0 || !isGlowStyle(type)) return
        val accent = runCatching { Color.parseColor(colorHex) }.getOrDefault(rainbowColors[0])
        val minSide = min(width, height).toFloat()
        val cx = width * 0.5f
        val cy = height * 0.44f
        val beat = bassEnergy.coerceIn(0f, 1f)

        fun level(index: Int): Float = spectrumBands.getOrElse(index.mod(spectrumBands.size.coerceAtLeast(1))) { beat }.coerceIn(0f, 1f)
        fun color(index: Int): Int = if (index == 0) accent else rainbowColors[index.mod(rainbowColors.size)]

        when (type) {
            VisualizerType.RADIAL_BURST -> {
                val inner = minSide * 0.12f * (1f + beat * 0.22f)
                val count = if (previewQuality) 48 else 72

                // Center pulsating bass ring
                wideStrokePaint.color = withAlpha(accent, (120 + beat * 120).toInt().coerceIn(120, 255))
                wideStrokePaint.strokeWidth = max(2f, minSide * (0.005f + beat * 0.006f))
                canvas.drawCircle(cx, cy, inner, wideStrokePaint)

                // Radiating audio frequency rays
                coreStrokePaint.strokeCap = Paint.Cap.ROUND
                for (i in 0 until count) {
                    val angle = i.toDouble() / count * PI * 2.0 + timeMs * 0.00015
                    val energy = level(i)
                    val rayLen = minSide * (0.04f + energy * 0.22f)
                    val x1 = cx + cos(angle).toFloat() * inner
                    val y1 = cy + sin(angle).toFloat() * inner
                    val x2 = cx + cos(angle).toFloat() * (inner + rayLen)
                    val y2 = cy + sin(angle).toFloat() * (inner + rayLen)
                    val tint = color(i / 6)

                    coreStrokePaint.color = withAlpha(tint, 180 + (energy * 75).toInt().coerceIn(0, 75))
                    coreStrokePaint.strokeWidth = max(1.5f, minSide * 0.004f)
                    canvas.drawLine(x1, y1, x2, y2, coreStrokePaint)
                }
            }

            VisualizerType.BASS_PULSE -> {
                val maxR = width * 0.45f
                for (ring in 0..3) {
                    val phase = ((timeMs * 0.0018 + ring * 0.28) % 1.0).toFloat()
                    val r = (phase * maxR) * (0.8f + beat * 0.4f)
                    val alpha = ((1f - phase) * (140 + beat * 115)).toInt().coerceIn(0, 255)
                    coreStrokePaint.color = withAlpha(accent, alpha)
                    coreStrokePaint.strokeWidth = max(1.5f, minSide * (0.006f * (1f - phase) + beat * 0.004f))
                    canvas.drawCircle(cx, cy, r, coreStrokePaint)
                }
                // Center core flare
                haloPaint.color = withAlpha(accent, (160 + beat * 95).toInt().coerceIn(160, 255))
                canvas.drawCircle(cx, cy, minSide * (0.035f + beat * 0.045f), haloPaint)
            }

            VisualizerType.AURORA_BLOOM -> {
                val drift = timeMs * 0.00025
                for (i in 0..4) {
                    val energy = level(i * 6)
                    val x = (0.5 + sin(drift + i * 1.25) * 0.32).toFloat() * width
                    val y = (0.36 + cos(drift * 0.8 + i * 0.95) * 0.20).toFloat() * height
                    val radius = minSide * (0.18f + energy * 0.18f)
                    haloPaint.color = withAlpha(color(i), 85 + (energy * 80).toInt().coerceIn(0, 100))
                    canvas.drawCircle(x, y, radius, haloPaint)
                }
            }

            VisualizerType.GLOW_ORBS -> {
                val count = if (previewQuality) 20 else 32
                for (i in 0 until count) {
                    val phase = timeMs * (0.00045 + (i % 5) * 0.00008) + i * (PI * 2.0 / count)
                    val z = (sin(phase * 1.7) * 0.5 + 0.5).toFloat()
                    val energy = level(i * 2)
                    val orbitX = minSide * (0.18f + z * 0.28f)
                    val orbitY = minSide * (0.14f + z * 0.22f)
                    val x = cx + cos(phase).toFloat() * orbitX
                    val y = cy + sin(phase * 1.2).toFloat() * orbitY
                    val radius = minSide * (0.008f + z * 0.012f + energy * 0.014f)

                    haloPaint.color = withAlpha(color(i), 120 + (energy * 100).toInt().coerceIn(0, 130))
                    canvas.drawCircle(x, y, radius, haloPaint)
                }
            }

            VisualizerType.NEON_RIBBON -> {
                val layers = if (previewQuality) 3 else 4
                for (layer in 0 until layers) {
                    cachedPath.reset()
                    val points = if (previewQuality) 48 else 80
                    for (i in 0..points) {
                        val x = width * i / points.toFloat()
                        val energy = level(i + layer * 7)
                        val envelope = 0.035f + energy * 0.14f
                        val y = height * (0.42f + layer * 0.08f) +
                                sin(i * 0.11 + timeMs * 0.002 + layer * 0.85).toFloat() * height * envelope
                        if (i == 0) cachedPath.moveTo(x, y) else cachedPath.lineTo(x, y)
                    }
                    val tint = color(layer)
                    coreStrokePaint.color = withAlpha(tint, 210 - layer * 30)
                    coreStrokePaint.strokeWidth = max(1.5f, minSide * 0.0035f)
                    canvas.drawPath(cachedPath, coreStrokePaint)
                }
            }

            VisualizerType.SPECTRUM_TUNNEL -> {
                val rotation = timeMs * 0.0004
                val layers = if (previewQuality) 6 else 10
                for (layer in 0 until layers) {
                    val depth = (layer + 1) / (layers + 1).toFloat()
                    val energy = level(layer * 3)
                    val pulse = 1f + energy * 0.22f
                    val halfW = width * (0.05f + depth * 0.44f) * pulse
                    val halfH = height * (0.03f + depth * 0.35f) * pulse
                    cachedRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                    val tint = color(layer)

                    canvas.save()
                    canvas.rotate((rotation * 57.3 + layer * 3.0).toFloat(), cx, cy)
                    coreStrokePaint.color = withAlpha(tint, 140 + (energy * 100).toInt().coerceIn(0, 100))
                    coreStrokePaint.strokeWidth = max(1.2f, minSide * 0.003f)
                    canvas.drawRoundRect(cachedRect, minSide * 0.025f, minSide * 0.025f, coreStrokePaint)
                    canvas.restore()
                }
            }

            else -> Unit
        }
    }

    fun isGlowStyle(type: VisualizerType): Boolean = when (type) {
        VisualizerType.AURORA_BLOOM,
        VisualizerType.GLOW_ORBS,
        VisualizerType.NEON_RIBBON,
        VisualizerType.RADIAL_BURST,
        VisualizerType.BASS_PULSE,
        VisualizerType.SPECTRUM_TUNNEL -> true
        else -> false
    }

    /** Converts precomputed waveform peaks into pseudo-spectrum bands when FFT is not yet ready. */
    fun bandsFromEnvelope(peaks: List<Float>, timeMs: Long, durationMs: Long): FloatArray {
        if (peaks.isEmpty()) return FloatArray(32) { 0.08f }
        val progress = (timeMs.coerceAtLeast(0L).toDouble() / durationMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val center = (progress * (peaks.size - 1)).toInt().coerceIn(0, peaks.lastIndex)
        val env = peaks[center].coerceIn(0f, 1f)

        return FloatArray(32) { band ->
            val harmonic = sin(timeMs * 0.005 + band * 0.45).toFloat() * 0.12f
            val decay = (1f - (band / 35f)).coerceIn(0.4f, 1.2f)
            (env * decay + harmonic).coerceIn(0.04f, 1f)
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
