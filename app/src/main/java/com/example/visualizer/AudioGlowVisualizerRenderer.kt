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
        bassEnergy: Float
    ) {
        if (width <= 0 || height <= 0 || !isGlowStyle(type)) return
        val accent = runCatching { Color.parseColor(colorHex) }.getOrDefault(palette[0])
        val minSide = min(width, height).toFloat()
        val cx = width * 0.5f
        val cy = height * 0.43f
        val beat = bassEnergy.coerceIn(0f, 1f)
        fun level(index: Int): Float = spectrumBands.getOrElse(index.mod(spectrumBands.size.coerceAtLeast(1))) { beat }.coerceIn(0f, 1f)
        fun color(index: Int): Int = if (index == 0) accent else palette[index.mod(palette.size)]
        fun glow(x: Float, y: Float, radius: Float, tint: Int, alpha: Int = 180) {
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
                for (i in 0..80) {
                    val x = width * i / 80f
                    val band = level(i * 3)
                    val y = height * (0.52f + sin(i * 0.095 + timeMs * 0.0014).toFloat() * (0.06f + band * 0.12f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, haze)
            }
            VisualizerType.GLOW_ORBS -> {
                for (i in 0 until 34) {
                    val phase = timeMs * (0.00045 + (i % 5) * 0.00007) + i * (PI * 2.0 / 34.0)
                    val z = (sin(phase * 1.7) * 0.5 + 0.5).toFloat()
                    val energy = level(i * 3)
                    val orbitX = minSide * (0.16f + z * 0.28f)
                    val orbitY = minSide * (0.12f + z * 0.24f)
                    val x = cx + cos(phase).toFloat() * orbitX
                    val y = cy + sin(phase * 1.23).toFloat() * orbitY
                    val radius = minSide * (0.006f + z * 0.009f + energy * 0.012f)
                    glow(x, y, radius * 4.8f, color(i), 95 + (energy * 130).toInt())
                    val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color(i), 150 + (z * 105).toInt()) }
                    canvas.drawCircle(x, y, radius, core)
                }
                glow(cx, cy, minSide * (0.16f + beat * 0.08f), accent, 80 + (beat * 100).toInt())
            }
            VisualizerType.NEON_RIBBON -> {
                for (layer in 0..4) {
                    val path = Path()
                    val depth = layer / 4f
                    for (i in 0..120) {
                        val x = width * i / 120f
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
                for (i in 0 until count) {
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
                for (layer in 0..10) {
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
                paint.alpha = 242
                paint.setShadowLayer(height * 0.008f, 0f, 0f, tint)
                canvas.drawPath(bar, paint)
                paint.clearShadowLayer()
                val reflectionTop = baseY + height * 0.012f + segment * (segmentHeight + segmentGap)
                val reflection = Path().apply {
                    moveTo(x, reflectionTop); lineTo(x + barWidth - skew, reflectionTop)
                    lineTo(x + barWidth, reflectionTop + segmentHeight); lineTo(x + skew, reflectionTop + segmentHeight); close()
                }
                paint.alpha = (82 * (1f - segment / (stack + 1f))).toInt()
                canvas.drawPath(reflection, paint)
            }
            val peakY = baseY - stack * (segmentHeight + segmentGap) - height * 0.018f
            val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; strokeWidth = max(1f, height * 0.0024f)
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

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
