package com.winsmediatama.lyricmakerpro.visualizer

import android.graphics.Canvas
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

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
