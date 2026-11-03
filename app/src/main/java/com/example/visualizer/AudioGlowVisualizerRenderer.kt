package com.winsmediatama.lyricmakerpro.visualizer

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.winsmediatama.lyricmakerpro.model.VisualizerType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Audio Glow-inspired original visualizer artwork shared by the editor preview and MP4 export.
 * It intentionally uses native Canvas primitives so the exact same renderer can be used
 * by Compose's preview canvas and Media3's bitmap overlay.
 */
object AudioGlowVisualizerRenderer {
    private val palette = intArrayOf(
        Color.rgb(0, 245, 212),
        Color.rgb(255, 0, 127),
        Color.rgb(120, 80, 255),
        Color.rgb(45, 190, 255),
        Color.rgb(255, 210, 80)
    )


    /** Cool Bars: transparent neon spectrum with mirrored reflections and a fine waveform mesh. */
    fun drawCoolBars(canvas: Canvas, width: Int, height: Int, timeMs: Long, bands: FloatArray) {
        if (width <= 0 || height <= 0) return
        val centerY = height * 0.60f
        val minSide = min(width, height).toFloat()
        val count = 64
        val step = width.toFloat() / count
        val barWidth = (step * 0.34f).coerceAtLeast(1.2f)
        val colors = intArrayOf(
            Color.rgb(255, 170, 0), Color.rgb(255, 55, 35), Color.rgb(255, 0, 145),
            Color.rgb(185, 45, 255), Color.rgb(45, 110, 255), Color.rgb(0, 220, 255),
            Color.rgb(0, 245, 210), Color.rgb(255, 190, 0)
        )
        fun energy(i: Int): Float {
            if (bands.isEmpty()) return 0.08f
            val pos = i * (bands.size - 1).coerceAtLeast(1) / (count - 1).toFloat()
            val lo = pos.toInt().coerceIn(0, bands.lastIndex)
            val hi = (lo + 1).coerceAtMost(bands.lastIndex)
            val raw = bands[lo] * (1f - (pos - lo)) + bands[hi] * (pos - lo)
            val shimmer = 0.92f + 0.08f * sin(timeMs * 0.004 + i * 0.7).toFloat()
            return (raw.coerceIn(0.025f, 1f) * shimmer).coerceIn(0.02f, 1f)
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        val reflection = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        val mesh = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, minSide * 0.001f) }
        val wave = Path()
        val reflectedWave = Path()
        for (i in 0 until count) {
            val x = (i + 0.5f) * step
            val level = energy(i)
            val h = height * (0.025f + level * 0.30f)
            val color = colors[(i * colors.size / count).coerceIn(0, colors.lastIndex)]
            glow.color = withAlpha(color, 105)
            glow.strokeWidth = max(2f, step * 0.72f)
            glow.setShadowLayer(minSide * 0.012f, 0f, 0f, color)
            canvas.drawLine(x, centerY - h, x, centerY + h * 0.22f, glow)
            glow.clearShadowLayer()
            core.color = withAlpha(color, 235)
            core.strokeWidth = max(1.2f, barWidth * 0.58f)
            canvas.drawLine(x, centerY - h, x, centerY, core)
            reflection.color = withAlpha(color, (85 + level * 65).toInt())
            reflection.strokeWidth = max(1f, barWidth * 0.38f)
            canvas.drawLine(x, centerY + 2f, x, centerY + h * 0.58f, reflection)
            val y = centerY - h * 0.04f + sin(timeMs * 0.002 + i * 0.31).toFloat() * height * 0.008f
            if (i == 0) {
                wave.moveTo(x, y)
                reflectedWave.moveTo(x, centerY + (centerY - y) * 0.22f)
            } else {
                wave.lineTo(x, y)
                reflectedWave.lineTo(x, centerY + (centerY - y) * 0.22f)
            }
        }
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, minSide * 0.002f)
            color = Color.argb(210, 90, 235, 255)
            setShadowLayer(minSide * 0.012f, 0f, 0f, Color.CYAN)
        }
        canvas.drawPath(wave, wavePaint)
        wavePaint.color = Color.argb(95, 70, 175, 255)
        wavePaint.strokeWidth = max(1f, minSide * 0.001f)
        wavePaint.clearShadowLayer()
        canvas.drawPath(reflectedWave, wavePaint)
        mesh.color = Color.argb(58, 75, 205, 255)
        for (i in 0 until 10) {
            val x = width * i / 9f
            canvas.drawLine(x, centerY + height * 0.015f, x + width * 0.075f, centerY + height * (0.07f + (i % 3) * 0.018f), mesh)
            canvas.drawLine(x, centerY - height * 0.012f, x - width * 0.045f, centerY - height * (0.035f + (i % 2) * 0.02f), mesh)
        }
    }

    fun isGlowStyle(type: VisualizerType): Boolean = when (type) {
        VisualizerType.AURORA_BLOOM,
        VisualizerType.GLOW_ORBS,
        VisualizerType.NEON_RIBBON,
        VisualizerType.RADIAL_BURST,
        VisualizerType.SPECTRUM_TUNNEL -> true
        else -> false
    }

    fun bandsFromEnvelope(peaks: List<Float>, timeMs: Long, durationMs: Long): FloatArray {
        if (peaks.isEmpty()) return FloatArray(32) { 0.08f }
        val progress = (timeMs.coerceAtLeast(0L).toDouble() / durationMs.coerceAtLeast(1L))
            .coerceIn(0.0, 1.0)
        val center = (progress * (peaks.size - 1)).toInt().coerceIn(0, peaks.lastIndex)
        return FloatArray(32) { band ->
            val spread = 1 + band / 4
            val left = peaks[(center - spread).coerceAtLeast(0)].coerceIn(0f, 1f)
            val mid = peaks[center].coerceIn(0f, 1f)
            val right = peaks[(center + spread).coerceAtMost(peaks.lastIndex)].coerceIn(0f, 1f)
            (mid * 0.58f + (left + right) * 0.21f).coerceIn(0.025f, 1f)
        }
    }

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
        val accent = runCatching { Color.parseColor(colorHex) }.getOrDefault(palette[0])
        val minSide = min(width, height).toFloat()
        val cx = width * 0.5f
        val cy = height * 0.43f
        val beat = bassEnergy.coerceIn(0f, 1f)
        fun level(index: Int): Float = spectrumBands.getOrElse(index.mod(spectrumBands.size.coerceAtLeast(1))) { beat }.coerceIn(0f, 1f)
        fun color(index: Int): Int = if (index == 0) accent else palette[index.mod(palette.size)]
        val previewGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        fun glow(x: Float, y: Float, radius: Float, tint: Int, alpha: Int = 180) {
            if (previewQuality) {
                previewGlowPaint.color = withAlpha(tint, alpha)
                canvas.drawCircle(x, y, radius * 0.62f, previewGlowPaint)
                return
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    x, y, radius,
                    intArrayOf(withAlpha(tint, alpha), withAlpha(tint, (alpha * 0.28f).toInt()), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(x, y, radius, paint)
        }

        when (type) {
            VisualizerType.AURORA_BLOOM -> {
                val drift = timeMs * 0.00022
                for (i in 0..5) {
                    val energy = level(i * 5)
                    val x = (0.5 + sin(drift + i * 1.13) * 0.34).toFloat() * width
                    val y = (0.34 + cos(drift * 0.8 + i * 0.91) * 0.22).toFloat() * height
                    val radius = minSide * (0.16f + energy * 0.16f)
                    glow(x, y, radius, color(i), 115 + (energy * 100).toInt())
                }
                val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = minSide * 0.012f
                    color = withAlpha(accent, 90 + (beat * 100).toInt())
                    setShadowLayer(minSide * 0.035f, 0f, 0f, accent)
                }
                val path = Path()
                for (i in 0..(if (previewQuality) 40 else 80)) {
                    val x = width * i / (if (previewQuality) 40f else 80f)
                    val band = level(i * 3)
                    val y = height * (0.52f + sin(i * 0.095 + timeMs * 0.0014).toFloat() * (0.06f + band * 0.12f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, haze)
            }
            VisualizerType.GLOW_ORBS -> {
                for (i in 0 until (if (previewQuality) 16 else 34)) {
                    val phase = timeMs * (0.00045 + (i % 5) * 0.00007) + i * (PI * 2.0 / 34.0)
                    val z = (sin(phase * 1.7) * 0.5 + 0.5).toFloat()
                    val energy = level(i * 3)
                    val orbitX = minSide * (0.16f + z * 0.28f)
                    val orbitY = minSide * (0.12f + z * 0.24f)
                    val x = cx + cos(phase).toFloat() * orbitX
                    val y = cy + sin(phase * 1.23).toFloat() * orbitY
                    val radius = minSide * (0.006f + z * 0.009f + energy * 0.012f)
                    glow(x, y, radius * 4.8f, color(i), 95 + (energy * 130).toInt())
                    if (previewQuality) {
                        previewGlowPaint.color = withAlpha(color(i), 150 + (z * 105).toInt())
                        canvas.drawCircle(x, y, radius, previewGlowPaint)
                    } else {
                        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color(i), 150 + (z * 105).toInt()) }
                        canvas.drawCircle(x, y, radius, core)
                    }
                }
                glow(cx, cy, minSide * (0.16f + beat * 0.08f), accent, 80 + (beat * 100).toInt())
            }
            VisualizerType.NEON_RIBBON -> {
                for (layer in 0..(if (previewQuality) 2 else 4)) {
                    val path = Path()
                    val depth = layer / 4f
                    for (i in 0..(if (previewQuality) 60 else 120)) {
                        val x = width * i / (if (previewQuality) 60f else 120f)
                        val energy = level(i + layer * 7)
                        val envelope = 0.035f + energy * 0.12f
                        val y = height * (0.43f + layer * 0.075f) +
                            sin(i * 0.105 + timeMs * 0.0018 + layer * 0.8).toFloat() * height * envelope * (1f - depth * 0.32f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    val tint = color(layer)
                    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = minSide * 0.018f
                        this.color = withAlpha(tint, 65)
                        setShadowLayer(minSide * 0.025f, 0f, 0f, tint)
                    }
                    canvas.drawPath(path, halo)
                    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = minSide * (0.0035f - depth * 0.0007f)
                        this.color = withAlpha(tint, 220 - layer * 22)
                    }
                    canvas.drawPath(path, line)
                }
            }
            VisualizerType.RADIAL_BURST -> {
                val inner = minSide * 0.105f
                val count = 96
                for (i in 0 until (if (previewQuality) 48 else count)) {
                    val angle = i.toDouble() / count * PI * 2.0 + timeMs * 0.00012
                    val energy = level(i * 2)
                    val length = minSide * (0.055f + energy * 0.19f)
                    val x1 = cx + cos(angle).toFloat() * inner
                    val y1 = cy + sin(angle).toFloat() * inner
                    val x2 = cx + cos(angle).toFloat() * (inner + length)
                    val y2 = cy + sin(angle).toFloat() * (inner + length)
                    val tint = color(i / 20)
                    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeWidth = minSide * 0.012f
                        this.color = withAlpha(tint, 55 + (energy * 60).toInt())
                        setShadowLayer(minSide * 0.018f, 0f, 0f, tint)
                    }
                    canvas.drawLine(x1, y1, x2, y2, halo)
                    val spoke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeWidth = minSide * 0.0045f
                        this.color = withAlpha(tint, 150 + (energy * 105).toInt())
                    }
                    canvas.drawLine(x1, y1, x2, y2, spoke)
                }
                glow(cx, cy, inner * (1.8f + beat * 0.8f), accent, 120 + (beat * 100).toInt())
            }
            VisualizerType.SPECTRUM_TUNNEL -> {
                val rotation = timeMs * 0.00045
                for (layer in 0..(if (previewQuality) 5 else 10)) {
                    val depth = (layer + 1) / 11f
                    val energy = level(layer * 3)
                    val pulse = 1f + energy * 0.18f
                    val halfW = width * (0.045f + depth * 0.46f) * pulse
                    val halfH = height * (0.025f + depth * 0.36f) * pulse
                    val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                    val tint = color(layer)
                    canvas.save()
                    canvas.rotate((rotation * 57.2958 + layer * 2.4).toFloat(), cx, cy)
                    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = minSide * 0.014f
                        this.color = withAlpha(tint, 65 + (energy * 80).toInt())
                        setShadowLayer(minSide * 0.025f, 0f, 0f, tint)
                    }
                    canvas.drawRoundRect(rect, minSide * 0.025f, minSide * 0.025f, halo)
                    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = minSide * 0.0035f
                        this.color = withAlpha(tint, 130 + (energy * 110).toInt())
                    }
                    canvas.drawRoundRect(rect, minSide * 0.025f, minSide * 0.025f, edge)
                    canvas.restore()
                }
                glow(cx, cy, minSide * (0.08f + beat * 0.08f), accent, 95 + (beat * 120).toInt())
            }
            else -> Unit
        }
    }

    /** Reference-style rainbow spectrum bars with metadata header and mirrored neon reflection. */
    fun drawClassicSpectrum(canvas: Canvas, width: Int, height: Int, timeMs: Long,
        bands: FloatArray, artist: String, trackTitle: String) {
        if (width <= 0 || height <= 0) return
        // This renderer is an overlay above imported photos/videos. Never paint an
        // opaque full-frame background here; preserve the underlying media pixels.
        val minSide = min(width, height).toFloat()
        val baseY = height * 0.665f
        val count = 16
        val gap = width * 0.009f
        val totalWidth = width * 0.88f
        val barWidth = (totalWidth - gap * (count - 1)) / count
        val segmentGap = height * 0.006f
        val segmentHeight = height * 0.021f
        val rainbow = intArrayOf(
            Color.rgb(55,92,255), Color.rgb(65,55,255), Color.rgb(170,35,255), Color.rgb(238,25,150),
            Color.rgb(255,35,75), Color.rgb(255,75,35), Color.rgb(255,130,25), Color.rgb(255,175,25),
            Color.rgb(255,220,30), Color.rgb(255,240,30), Color.rgb(220,255,25), Color.rgb(125,255,20),
            Color.rgb(70,255,25), Color.rgb(45,245,40), Color.rgb(55,225,35), Color.rgb(80,220,45)
        )
        val centerX = width * 0.5f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            textSize = height * 0.078f
            setShadowLayer(height * 0.006f, 0f, 0f, Color.WHITE)
        }
        val subtitlePaint = Paint(titlePaint).apply { textSize = height * 0.052f }
        artist.trim().takeIf { it.isNotEmpty() && !it.equals("unknown", true) }?.let {
            canvas.drawText(it, centerX, height * 0.265f, titlePaint)
        }
        trackTitle.trim().takeIf { it.isNotEmpty() && !it.equals("unknown", true) }?.let {
            canvas.drawText(it, centerX, height * 0.315f, subtitlePaint)
        }

        // Fine, colorful floating particles over a pure black canvas.
        val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 115) {
            val seed = i * 127.1
            val x = ((sin(seed * 0.73) * 0.5 + 0.5) * width).toFloat()
            val y = ((cos(seed * 1.17) * 0.5 + 0.5) * height * 0.76f).toFloat()
            val twinkle = (0.35 + 0.65 * (0.5 + 0.5 * sin(timeMs * 0.002 + seed))).toFloat()
            val radius = minSide * (0.0011f + (i % 4) * 0.00055f)
            val tint = rainbow[i % rainbow.size]
            particlePaint.color = withAlpha(tint, (35 + twinkle * 150).toInt())
            particlePaint.setShadowLayer(radius * 5f, 0f, 0f, tint)
            canvas.drawCircle(x, y, radius * (0.7f + twinkle), particlePaint)
            particlePaint.clearShadowLayer()
        }

        val startX = (width - totalWidth) / 2f
        for (i in 0 until count) {
            val energy = bands.getOrElse(i) { 0.25f }.coerceIn(0.04f, 1f)
            val stack = (2 + (energy * 7.2f * (0.88f + 0.12f * sin(timeMs * 0.004 + i * 0.7).toFloat())).toInt()).coerceIn(2, 9)
            val x = startX + i * (barWidth + gap)
            val skew = barWidth * 0.10f
            val tint = rainbow[i]
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
            // Colored haze behind each column.
            val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x + barWidth/2f, baseY, height * 0.15f,
                    intArrayOf(withAlpha(tint, 48), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(x + barWidth/2f, baseY, height * 0.15f, haze)
            for (segment in 0 until stack) {
                val top = baseY - (segment + 1) * (segmentHeight + segmentGap)
                val bar = Path().apply {
                    moveTo(x + skew, top); lineTo(x + barWidth, top)
                    lineTo(x + barWidth - skew, top + segmentHeight); lineTo(x, top + segmentHeight); close()
                }
                paint.alpha = 185
                paint.setShadowLayer(height * 0.008f, 0f, 0f, tint)
                canvas.drawPath(bar, paint)
                paint.clearShadowLayer()
                val reflectionTop = baseY + height * 0.012f + segment * (segmentHeight + segmentGap)
                val reflection = Path().apply {
                    moveTo(x, reflectionTop); lineTo(x + barWidth - skew, reflectionTop)
                    lineTo(x + barWidth, reflectionTop + segmentHeight); lineTo(x + skew, reflectionTop + segmentHeight); close()
                }
                paint.alpha = (58 * (1f - segment / (stack + 1f))).toInt()
                canvas.drawPath(reflection, paint)
            }
            val peakY = baseY - stack * (segmentHeight + segmentGap) - height * 0.018f
            val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(Color.WHITE, 190); strokeWidth = max(1f, height * 0.0024f)
                setShadowLayer(height * 0.008f, 0f, 0f, Color.WHITE)
            }
            canvas.drawLine(x - barWidth * 0.05f, peakY, x + barWidth * 1.05f, peakY, cap)
        }
        val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, baseY, width.toFloat(), baseY,
                intArrayOf(rainbow.first(), Color.MAGENTA, Color.RED, Color.YELLOW, Color.GREEN),
                null, Shader.TileMode.CLAMP)
            strokeWidth = height * 0.008f
            setShadowLayer(height * 0.024f, 0f, 0f, Color.WHITE)
        }
        canvas.drawLine(width * 0.015f, baseY, width * 0.985f, baseY, baseline)
        baseline.shader = null; baseline.color = Color.WHITE; baseline.strokeWidth = height * 0.002f
        canvas.drawLine(width * 0.015f, baseY, width * 0.985f, baseY, baseline)
    }

    /** Transparent multicolor sine-wave mesh inspired by the supplied reference. */
    fun drawSinBars(canvas: Canvas, width: Int, height: Int, timeMs: Long, bands: FloatArray) {
        if (width <= 0 || height <= 0) return
        val minSide = min(width, height).toFloat()
        val centerY = height * 0.53f
        val count = 112
        val stepX = width.toFloat() / (count - 1)
        // Seven lanes are rendered below, so keep one dedicated tint per lane.
        // The former six-color array caused colors[6] to crash on the final lane.
        val colors = intArrayOf(
            Color.CYAN,
            Color.rgb(0, 145, 255),
            Color.rgb(45, 245, 220),
            Color.rgb(110, 100, 255),
            Color.rgb(0, 255, 190),
            Color.rgb(30, 190, 255),
            Color.rgb(190, 80, 255)
        )
        fun energy(i: Int): Float = bands.getOrElse((i * 32 / count).coerceIn(0, 31)) { 0.2f }.coerceIn(0.025f, 1f)
        fun wave(i: Int, lane: Int): Float {
            val x = i.toDouble() / (count - 1)
            val env = (0.12 + 0.88 * (0.5 + 0.5 * sin(x * PI * 2.0 + 0.35))).toFloat()
            val phase = x * 7.0 * PI + timeMs * 0.0017 + lane * 0.22
            val amp = height * (0.012f + energy(i) * 0.31f) * env
            return centerY + sin(phase).toFloat() * amp + sin(phase * 2.15 + lane * 0.17).toFloat() * amp * 0.16f
        }
        // Seven translucent waveform strands.
        for (lane in -3..3) {
            val path = Path()
            for (i in 0 until count) {
                val x = i * stepX
                if (i == 0) path.moveTo(x, wave(i,lane)) else path.lineTo(x, wave(i,lane))
            }
            val tint = colors.getOrElse(lane + 3) { colors[(lane + 3).mod(colors.size)] }
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = minSide * 0.010f
                color = withAlpha(tint, 28); setShadowLayer(minSide * 0.018f,0f,0f,tint)
            }
            canvas.drawPath(path, halo)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = minSide * (if (lane == 0) 0.0034f else 0.0015f)
                color = withAlpha(tint, if (lane == 0) 175 else 68)
            }
            canvas.drawPath(path, line)
        }
        // Fine triangular mesh and glowing nodes.
        val mesh = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f,minSide*0.00085f) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (i in 0 until count-1) {
            val x1=i*stepX; val x2=(i+1)*stepX
            val top1=wave(i,-2); val mid1=wave(i,0); val bot1=wave(i,2)
            val top2=wave(i+1,-2); val mid2=wave(i+1,0); val bot2=wave(i+1,2)
            val tint=colors[(i/18)%colors.size]
            mesh.color=withAlpha(tint,48+(energy(i)*65).toInt())
            canvas.drawLine(x1,top1,x2,mid2,mesh); canvas.drawLine(x1,mid1,x2,top2,mesh)
            canvas.drawLine(x1,mid1,x2,bot2,mesh); canvas.drawLine(x1,bot1,x2,mid2,mesh)
            if (i%3==0) {
                val r=minSide*(0.0014f+energy(i)*0.0032f)
                dot.color=withAlpha(tint,115+(energy(i)*130).toInt())
                dot.setShadowLayer(r*5f,0f,0f,tint); canvas.drawCircle(x1,mid1,r,dot); dot.clearShadowLayer()
            }
        }
        // Bright central beam remains an overlay; no opaque background is drawn.
        val beam=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE; strokeWidth=minSide*0.006f
            shader=LinearGradient(0f,centerY,width.toFloat(),centerY,intArrayOf(Color.TRANSPARENT,Color.CYAN,Color.rgb(0,145,255),Color.CYAN,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            setShadowLayer(minSide*0.025f,0f,0f,Color.CYAN)
        }
        canvas.drawLine(0f,centerY,width.toFloat(),centerY,beam)
        beam.shader=null; beam.clearShadowLayer(); beam.strokeWidth=max(1f,minSide*0.0012f); beam.color=withAlpha(Color.WHITE,165)
        canvas.drawLine(0f,centerY,width.toFloat(),centerY,beam)
        for (i in 0 until 48) {
            val seed=i*91.7
            val x=((sin(seed*0.73)*0.5+0.5)*width).toFloat()
            val n=(x/width*(count-1)).toInt().coerceIn(0,count-1)
            val y=wave(n,(i%7)-3)+cos(seed*1.31+timeMs*0.001).toFloat()*height*0.035f
            val r=minSide*(0.0014f+(i%4)*0.0008f); val tint=colors[i%colors.size]
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=withAlpha(tint,120+((sin(timeMs*0.003+seed)*0.5+0.5)*135).toInt())
                setShadowLayer(r*7f,0f,0f,tint)
            }
            canvas.drawCircle(x,y,r,p)
        }
    }

    /**
     * Transparent 3D terrain spectrum. The layered ridges and perspective mesh react
     * to the audio envelope while leaving the imported photo/video visible underneath.
     */
    fun drawTerrainBars(canvas: Canvas, width: Int, height: Int, timeMs: Long, bands: FloatArray) {
        if (width <= 0 || height <= 0) return
        val minSide = min(width, height).toFloat()
        val columns = 84
        val rows = 9
        val horizon = height * 0.43f
        val stepX = width.toFloat() / (columns - 1)
        val colors = intArrayOf(
            Color.rgb(0, 235, 255),
            Color.rgb(35, 155, 255),
            Color.rgb(95, 95, 255),
            Color.rgb(190, 65, 255),
            Color.rgb(255, 55, 190),
            Color.rgb(65, 220, 255),
            Color.rgb(0, 245, 205)
        )
        fun energy(column: Int): Float {
            val index = (column * 32 / columns).coerceIn(0, 31)
            return bands.getOrElse(index) { 0.12f }.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.12f
        }
        fun point(column: Int, row: Int): Pair<Float, Float> {
            val xNorm = column.toFloat() / (columns - 1)
            val depth = row.toFloat() / (rows - 1)
            val x = column * stepX
            val band = energy(column)
            val travel = timeMs * 0.00048 + row * 0.31
            val ridgeA = kotlin.math.abs(sin(xNorm * PI * 2.25 + travel * 0.28))
            val ridgeB = kotlin.math.abs(sin(xNorm * PI * 4.1 - travel * 0.19 + 0.7))
            val envelope = (0.24 + ridgeA * 0.48 + ridgeB * 0.28).toFloat()
            val amplitude = height * (0.018f + band * 0.17f) * (0.42f + depth * 0.9f)
            val wave = (envelope * amplitude) +
                sin(xNorm * PI * 7.0 + travel).toFloat() * height * (0.008f + band * 0.025f)
            val y = horizon + depth * height * 0.105f - wave
            return x to y
        }

        // Back-to-front terrain ridges. Strokes only: never paint a solid backdrop.
        for (row in 0 until rows) {
            val path = Path()
            for (column in 0 until columns) {
                val (x, y) = point(column, row)
                if (column == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val tint = colors[(row * 3) % colors.size]
            val alpha = (48 + row * 7).coerceIn(48, 104)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = minSide * (0.009f - row * 0.00045f).coerceAtLeast(0.003f)
                color = withAlpha(tint, (alpha * 0.52f).toInt())
                setShadowLayer(minSide * 0.018f, 0f, 0f, tint)
            }
            canvas.drawPath(path, glow)
            val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = max(1f, minSide * (0.0027f - row * 0.00012f))
                color = withAlpha(tint, alpha)
            }
            canvas.drawPath(path, ridge)
        }

        // Perspective cross-mesh connects the ridges into a 3D wireframe landscape.
        val mesh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, minSide * 0.00105f)
        }
        for (column in 0 until columns step 4) {
            val path = Path()
            for (row in 0 until rows) {
                val (x, y) = point(column, row)
                if (row == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val tint = colors[(column / 12) % colors.size]
            mesh.color = withAlpha(tint, (34 + energy(column) * 66).toInt())
            canvas.drawPath(path, mesh)
        }

        // A few luminous audio peaks emphasize the terrain silhouette without filling it.
        for (column in 2 until columns step 7) {
            val (x, y) = point(column, rows - 1)
            val band = energy(column)
            val radius = minSide * (0.002f + band * 0.004f)
            val tint = colors[(column / 7) % colors.size]
            val node = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(tint, (80 + band * 115).toInt())
                setShadowLayer(radius * 6f, 0f, 0f, tint)
            }
            canvas.drawCircle(x, y, radius, node)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
