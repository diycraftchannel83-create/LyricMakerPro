package com.winsmediatama.lyricmakerpro.visualizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.winsmediatama.lyricmakerpro.model.VisualizerType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Transparent, audio-reactive Canvas implementations of the 16 reference visualizers.
 * This renderer is shared by editor preview and MP4 export so geometry stays consistent.
 * It deliberately never paints a background or an opaque bitmap.
 */
fun drawReferenceVisualizer(
    canvas: Canvas,
    width: Int,
    height: Int,
    type: VisualizerType,
    bands: FloatArray,
    bassEnergy: Float,
    timeMs: Long
) {
    if (width <= 0 || height <= 0) return
    val w = width.toFloat()
    val h = height.toFloat()
    val centerY = h * 0.5f
    val energy = bassEnergy.coerceIn(0f, 1f)
    fun level(i: Int): Float = bands.getOrElse(i.mod(max(1, bands.size))) { energy }.coerceIn(0f, 1f)
    fun color(i: Int, alpha: Int = 255): Int {
        val hue = ((i * 11f + 185f) % 360f)
        return Color.HSVToColor(alpha.coerceIn(0, 255), floatArrayOf(hue, 0.88f, 1f))
    }
    fun neonPaint(i: Int, style: Paint.Style = Paint.Style.STROKE, stroke: Float = max(1.5f, w * 0.004f)): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = style
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color(i)
            setShadowLayer(stroke * 2.8f, 0f, 0f, this.color)
        }
    fun areaPath(points: List<Pair<Float, Float>>, baseline: Float): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points.first().first, baseline)
        lineTo(points.first().first, points.first().second)
        points.drop(1).forEach { lineTo(it.first, it.second) }
        lineTo(points.last().first, baseline)
        close()
    }
    fun drawGradientArea(points: List<Pair<Float, Float>>, baseline: Float, top: Int, bottom: Int) {
        if (points.size < 2) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, baseline - h * 0.25f, 0f, baseline + h * 0.12f,
                intArrayOf(top, bottom), null, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(areaPath(points, baseline), p)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, w * 0.003f)
            strokeJoin = Paint.Join.ROUND
            color = top
            setShadowLayer(w * 0.012f, 0f, 0f, top)
        }
        val path = Path()
        points.forEachIndexed { index, pt -> if (index == 0) path.moveTo(pt.first, pt.second) else path.lineTo(pt.first, pt.second) }
        canvas.drawPath(path, line)
    }
    when (type) {
        VisualizerType.SPECTRUM_BARS_II -> {
            val count =  fortyEight()
            val gap = w * 0.003f
            val barW = (w * 0.76f / count) - gap
            val left = w * 0.12f
            val base = centerY
            for (i in 0 until count) {
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val wave = (0.16f + v * 0.84f) * (0.55f + 0.45f * abs(sin(i * 0.38 + timeMs * 0.002)))
                val bh = h * 0.30f * wave
                val x = left + i * (barW + gap)
                val p = neonPaint(i, Paint.Style.FILL, 0f).apply { clearShadowLayer(); alpha = 220 }
                canvas.drawRoundRect(x, base - bh, x + barW, base + bh * 0.56f, barW * 0.3f, barW * 0.3f, p)
            }
            canvas.drawLine(w * 0.08f, base, w * 0.92f, base, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 35, 135); alpha = 130; strokeWidth = max(1f, h * 0.0012f)
            })
        }
        VisualizerType.WAVE_MORPH -> {
            for (layer in 0..5) {
                val path = Path()
                val amp = h * (0.035f + energy * 0.12f) * (1f - layer * 0.11f)
                for (i in 0..120) {
                    val x = w * i / 120f
                    val taper = (1f - i / 135f).coerceAtLeast(0f)
                    val envelope = abs(sin(i * 0.105 + timeMs * 0.0025)).toFloat()
                    val y = centerY + sin(i * 0.12 + timeMs * 0.003 + layer * 0.42).toFloat() * amp * envelope * taper
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val p = neonPaint(layer * 4 + 1, Paint.Style.STROKE, max(2f, w * 0.006f - layer * 0.0006f))
                p.alpha = 210 - layer * 24
                canvas.drawPath(path, p)
            }
        }
        VisualizerType.EQUALIZER_COLUMNS -> {
            val count = 56
            val gap = w * 0.0025f
            val barW = w * 0.72f / count - gap
            val left = w * 0.14f
            for (i in 0 until count) {
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val heightFactor = 0.16f + v * 0.84f
                val bh = h * 0.31f * heightFactor
                val x = left + i * (barW + gap)
                val p = neonPaint(i * 2, Paint.Style.FILL, 0f).apply { clearShadowLayer(); alpha = 230 }
                canvas.drawRect(x, centerY - bh, x + barW, centerY + bh * 0.62f, p)
            }
        }
        VisualizerType.AREA_LINE_UP, VisualizerType.AREA_LINE_DUAL, VisualizerType.DUAL_AREA_WAVE -> {
            val pointsTop = (0..48).map { i ->
                val x = w * (0.08f + i / 48f * 0.84f)
                val v = level(i * bands.size.coerceAtLeast(1) / 49)
                val y = h * 0.43f - h * (0.04f + v * 0.17f) * (0.45f + abs(sin(i * 0.55 + timeMs * 0.0015)).toFloat() * 0.55f)
                x to y
            }
            drawGradientArea(pointsTop, h * 0.49f, Color.argb(170, 0, 240, 255), Color.argb(5, 0, 240, 255))
            if (type != VisualizerType.AREA_LINE_UP) {
                val pointsBottom = (0..48).map { i ->
                    val x = w * (0.08f + i / 48f * 0.84f)
                    val v = level((48 - i) * bands.size.coerceAtLeast(1) / 49)
                    val y = h * 0.56f + h * (0.035f + v * 0.16f) * (0.45f + abs(sin(i * 0.48 + timeMs * 0.0018)).toFloat() * 0.55f)
                    x to y
                }
                drawGradientArea(pointsBottom, h * 0.52f, Color.argb(190, 255, 0, 110), Color.argb(4, 255, 0, 110))
            }
        }
        VisualizerType.MULTI_WAVE_FLOW, VisualizerType.WAVE_RIBBON, VisualizerType.PEAK_WAVE -> {
            val layers = if (type == VisualizerType.PEAK_WAVE) 7 else 9
            for (layer in 0 until layers) {
                val path = Path()
                for (i in 0..120) {
                    val x = w * (0.08f + i / 120f * 0.84f)
                    val phase = i * 0.075 + timeMs * 0.002 + layer * 0.34
                    val l = level(i * bands.size.coerceAtLeast(1) / 121)
                    val envelope = if (type == VisualizerType.PEAK_WAVE) abs(sin(i * 0.045 + layer * 0.65)).toFloat() else 1f
                    val amp = h * (0.018f + l * 0.13f) * (1f - layer * 0.075f)
                    val y = centerY + sin(phase).toFloat() * amp * envelope
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val p = neonPaint(layer * 3, Paint.Style.STROKE, max(1.3f, w * 0.0028f))
                p.alpha = 220 - layer * 15
                canvas.drawPath(path, p)
            }
        }
        VisualizerType.PULSE_WAVE -> {
            val count = 84
            val gap = w * 0.002f
            val barW = w * 0.76f / count - gap
            for (i in 0 until count) {
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val envelope = 0.12f + v * 0.88f
                val bh = h * 0.20f * envelope
                val x = w * 0.12f + i * (barW + gap)
                val p = neonPaint(i * 3, Paint.Style.FILL, 0f).apply { clearShadowLayer(); alpha = 235 }
                canvas.drawRect(x, centerY - bh, x + barW, centerY + bh, p)
            }
            canvas.drawLine(w * 0.1f, centerY, w * 0.9f, centerY, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; alpha = 170; strokeWidth = 1.5f })
        }
        VisualizerType.DOT_SPECTRUM -> {
            val columns = 44
            val rows = 18
            val spacingX = w * 0.72f / columns
            val radius = max(1.2f, min(w, h) * 0.0032f)
            for (i in 0 until columns) {
                val v = level(i * bands.size.coerceAtLeast(1) / columns)
                val activeRows = (2 + v * (rows - 2)).toInt()
                for (j in 0 until activeRows) {
                    val x = w * 0.14f + i * spacingX
                    val y = centerY + (j - activeRows / 2f) * radius * 2.35f
                    val p = neonPaint(i * 2, Paint.Style.FILL, 0f).apply { clearShadowLayer(); alpha = (235 - j * 5).coerceIn(80, 235) }
                    canvas.drawCircle(x, y, radius, p)
                }
            }
        }
        VisualizerType.VERTICAL_PULSE -> {
            val count = 66
            for (i in 0 until count) {
                val x = w * (0.18f + i / (count - 1f) * 0.64f)
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val pulse = 0.12f + v * 0.88f
                val half = h * 0.24f * pulse * (0.72f + 0.28f * abs(sin(timeMs * 0.003 + i)))
                val p = neonPaint(i, Paint.Style.STROKE, max(1.2f, w * 0.0018f))
                p.alpha = 220
                canvas.drawLine(x, centerY - half, x, centerY + half, p)
            }
        }
        VisualizerType.CONTOUR_RIPPLE -> {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val base = min(w, h) * 0.11f
            for (layer in 0..7) {
                val path = Path()
                val radius = base * (1f + layer * 0.19f)
                for (i in 0..180) {
                    val a = i / 180f * (2f * PI.toFloat())
                    val l = level(i * bands.size.coerceAtLeast(1) / 181)
                    val r = radius * (1f + 0.11f * sin(a * 5f + timeMs * 0.001f).toFloat() + l * 0.08f)
                    val x = cx + cos(a) * r
                    val y = cy + sin(a) * r * 0.78f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val p = neonPaint(layer * 5, Paint.Style.STROKE, max(1.4f, w * 0.0023f))
                p.alpha = 235 - layer * 12
                canvas.drawPath(path, p)
            }
        }
        VisualizerType.DOT_WAVE_LINE -> {
            val count = 76
            val radius = max(1.4f, w * 0.0032f)
            for (i in 0 until count) {
                val x = w * (0.12f + i / (count - 1f) * 0.76f)
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val y = centerY + sin(i * 0.22 + timeMs * 0.0025).toFloat() * h * (0.035f + v * 0.13f)
                val p = neonPaint(i * 4, Paint.Style.FILL, 0f).apply { clearShadowLayer() }
                canvas.drawCircle(x, y, radius * (0.7f + v * 0.7f), p)
            }
        }
        VisualizerType.SPECTRUM_GLOW_LINE -> {
            val count = 72
            for (i in 0 until count) {
                val x = w * (0.12f + i / (count - 1f) * 0.76f)
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val pulse = 0.18f + v * 0.82f
                val half = h * 0.11f * pulse
                val p = neonPaint(i * 3, Paint.Style.STROKE, max(1.4f, w * 0.0022f))
                p.setShadowLayer(w * 0.014f, 0f, 0f, p.color)
                canvas.drawLine(x, centerY - half, x, centerY + half, p)
            }
        }
        VisualizerType.BEAT_STICKS -> {
            val count = 62
            val gap = w * 0.0025f
            val barW = w * 0.76f / count - gap
            for (i in 0 until count) {
                val v = level(i * bands.size.coerceAtLeast(1) / count)
                val envelope = 0.08f + v * 0.92f
                val hBar = h * 0.10f * envelope * (0.55f + 0.45f * abs(sin(i * 0.34 + timeMs * 0.002)))
                val x = w * 0.12f + i * (barW + gap)
                val p = neonPaint(i * 3, Paint.Style.STROKE, max(1.2f, barW * 0.38f))
                canvas.drawLine(x, centerY - hBar, x, centerY + hBar, p)
            }
        }
        else -> Unit
    }
}

private fun fortyEight(): Int = 48
