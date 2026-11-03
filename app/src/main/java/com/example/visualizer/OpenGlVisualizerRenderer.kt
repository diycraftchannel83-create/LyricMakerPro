package com.winsmediatama.lyricmakerpro.visualizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.EGL14
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shared GLES 2.0 off-screen visualizer renderer.
 *
 * Preview and Media3 export both call this exact renderer, so visualizer geometry,
 * colors, transparency and animation time stay consistent. The returned bitmap is
 * an RGBA overlay only; it never paints an opaque background over imported media.
 */
object OpenGlVisualizerRenderer {
    private val lock = Any()
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var displayInitialized = false
    private var program = 0
    private var positionLocation = -1
    private var resolutionLocation = -1
    private var timeLocation = -1
    private var typeLocation = -1
    private var bassLocation = -1
    private var colorLocation = -1
    private var bandsLocation = -1
    private var vertexBuffer: FloatBuffer? = null
    private var bitmapCache: Bitmap? = null
    private var pixelBuffer: ByteBuffer? = null
    private var argbBuffer: IntArray? = null

    private val vertexShader = """
        attribute vec2 aPosition;
        varying vec2 vUv;
        void main() {
            vUv = aPosition * 0.5 + 0.5;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform vec2 uResolution;
        uniform float uTime;
        uniform int uType;
        uniform float uBass;
        uniform vec3 uAccent;
        uniform float uBands[32];

        const float PI = 3.14159265359;
        float bandAt(int i) {
            if (i == 0) return clamp(uBands[0], 0.015, 1.0);
            if (i == 1) return clamp(uBands[1], 0.015, 1.0);
            if (i == 2) return clamp(uBands[2], 0.015, 1.0);
            if (i == 3) return clamp(uBands[3], 0.015, 1.0);
            if (i == 4) return clamp(uBands[4], 0.015, 1.0);
            if (i == 5) return clamp(uBands[5], 0.015, 1.0);
            if (i == 6) return clamp(uBands[6], 0.015, 1.0);
            if (i == 7) return clamp(uBands[7], 0.015, 1.0);
            if (i == 8) return clamp(uBands[8], 0.015, 1.0);
            if (i == 9) return clamp(uBands[9], 0.015, 1.0);
            if (i == 10) return clamp(uBands[10], 0.015, 1.0);
            if (i == 11) return clamp(uBands[11], 0.015, 1.0);
            if (i == 12) return clamp(uBands[12], 0.015, 1.0);
            if (i == 13) return clamp(uBands[13], 0.015, 1.0);
            if (i == 14) return clamp(uBands[14], 0.015, 1.0);
            if (i == 15) return clamp(uBands[15], 0.015, 1.0);
            if (i == 16) return clamp(uBands[16], 0.015, 1.0);
            if (i == 17) return clamp(uBands[17], 0.015, 1.0);
            if (i == 18) return clamp(uBands[18], 0.015, 1.0);
            if (i == 19) return clamp(uBands[19], 0.015, 1.0);
            if (i == 20) return clamp(uBands[20], 0.015, 1.0);
            if (i == 21) return clamp(uBands[21], 0.015, 1.0);
            if (i == 22) return clamp(uBands[22], 0.015, 1.0);
            if (i == 23) return clamp(uBands[23], 0.015, 1.0);
            if (i == 24) return clamp(uBands[24], 0.015, 1.0);
            if (i == 25) return clamp(uBands[25], 0.015, 1.0);
            if (i == 26) return clamp(uBands[26], 0.015, 1.0);
            if (i == 27) return clamp(uBands[27], 0.015, 1.0);
            if (i == 28) return clamp(uBands[28], 0.015, 1.0);
            if (i == 29) return clamp(uBands[29], 0.015, 1.0);
            if (i == 30) return clamp(uBands[30], 0.015, 1.0);
            if (i == 31) return clamp(uBands[31], 0.015, 1.0);
            return 0.08;
        }
        float hash(float n) { return fract(sin(n) * 43758.5453123); }
        float lineGlow(float d, float width) { return exp(-pow(d / max(width, 0.0005), 2.0)); }
        vec3 rainbow(float t) {
            return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
        }
        void addGlow(inout vec3 rgb, inout float alpha, vec3 c, float amount) {
            float a = clamp(amount, 0.0, 1.0);
            rgb += c * a;
            alpha = max(alpha, a * 0.88);
        }
        void main() {
            vec2 p = vUv;
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
            float t = uTime;
            float bass = clamp(uBass, 0.0, 1.0);
            vec3 rgb = vec3(0.0);
            float alpha = 0.0;

            if (uType == 0 || uType == 4) {
                float count = 16.0;
                float x = p.x * count;
                int idx = int(clamp(floor(x), 0.0, 15.0));
                float energy = bandAt(idx);
                float barH = 0.025 + energy * 0.29;
                float center = uType == 4 ? 0.665 : 0.62;
                float localX = abs(fract(x) - 0.5);
                float d = abs(p.y - center);
                float bar = step(localX, 0.34) * step(d, barH);
                vec3 c = rainbow(float(idx) / 15.0);
                addGlow(rgb, alpha, c, bar * 0.92);
                float reflection = step(localX, 0.24) * step(center, p.y) * step(p.y, center + barH * 0.55);
                addGlow(rgb, alpha, c, reflection * 0.30);
                float glow = exp(-localX * 18.0) * exp(-abs(p.y-center) * 5.0) * energy;
                addGlow(rgb, alpha, c, glow * 0.22);
            } else if (uType == 1) {
                float radius = length(uv);
                float angle = atan(uv.y, uv.x);
                float wave = 0.17 + bandAt(int(mod(floor((angle + PI) * 5.0), 32.0))) * 0.19;
                float d = abs(radius - wave - 0.025 * sin(angle * 8.0 + t * 2.0));
                addGlow(rgb, alpha, rainbow((angle + PI) / (2.0 * PI)), lineGlow(d, 0.012) * 0.95);
                float dots = lineGlow(fract((angle + PI) * 16.0) - 0.5, 0.10) * lineGlow(radius - wave, 0.035);
                addGlow(rgb, alpha, uAccent, dots * 0.5);
            } else if (uType == 2) {
                float x = p.x;
                float row = p.y;
                float ridge = 0.23 + 0.18 * sin(x * 13.0 + t * 1.1);
                ridge += bandAt(int(clamp(x * 31.0, 0.0, 31.0))) * 0.22;
                float d = abs(row - ridge);
                addGlow(rgb, alpha, rainbow(x * 0.85 + t * 0.025), lineGlow(d, 0.012) * 0.9);
                float grid = abs(fract(x * 18.0) - 0.5);
                addGlow(rgb, alpha, uAccent, lineGlow(grid, 0.018) * lineGlow(row-ridge, 0.055) * 0.45);
            } else if (uType == 3) {
                for (int i = 0; i < 28; i++) {
                    float fi = float(i);
                    float x = fract(hash(fi * 17.13) + sin(t * (0.25 + hash(fi)) + fi) * 0.10);
                    float y = fract(hash(fi * 7.31) + t * (0.035 + hash(fi + 3.0) * 0.06));
                    float r = 0.0025 + hash(fi + 9.0) * 0.004 + bandAt(i) * 0.006;
                    float d = length((p - vec2(x,y)) * vec2(uResolution.x/uResolution.y,1.0));
                    addGlow(rgb, alpha, rainbow(fi/28.0), lineGlow(d, r * 4.0) * 0.45);
                }
            } else if (uType == 5) {
                float radius = length(uv);
                for (int i = 0; i < 4; i++) {
                    float phase = fract(t * 0.45 + float(i) * 0.25);
                    float d = abs(radius - phase * 0.72);
                    addGlow(rgb, alpha, uAccent, lineGlow(d, 0.008 + bass * 0.012) * (1.0-phase) * 0.9);
                }
            } else if (uType == 6) {
                for (int i = 0; i < 6; i++) {
                    float fi = float(i);
                    vec2 c = vec2(0.5 + 0.27*sin(t*0.18+fi*1.1), 0.42 + 0.18*cos(t*0.15+fi));
                    float d = length((p-c)*vec2(1.0, uResolution.y/uResolution.x));
                    float haze = exp(-d*d/(0.025 + bandAt(i*4)*0.035));
                    addGlow(rgb, alpha, rainbow(fi/6.0), haze * 0.24);
                }
                float wave = abs(p.y - (0.48 + sin(p.x*18.0+t*1.5)*(0.025+bandAt(8)*0.08)));
                addGlow(rgb, alpha, uAccent, lineGlow(wave,0.009)*0.7);
            } else if (uType == 7) {
                for (int i = 0; i < 24; i++) {
                    float fi = float(i);
                    float a = t*0.35 + fi*0.2618;
                    float z = 0.5 + 0.5*sin(a*1.7);
                    vec2 c = vec2(0.5,0.43) + vec2(cos(a),sin(a*1.23))*vec2(0.12+z*0.25,0.10+z*0.20);
                    float d = length((p-c)*vec2(1.0,uResolution.y/uResolution.x));
                    float r = 0.004 + bandAt(i)*0.012;
                    addGlow(rgb, alpha, rainbow(fi/24.0), lineGlow(d,r*4.5)*0.8);
                }
            } else if (uType == 8) {
                for (int j = 0; j < 4; j++) {
                    float fj = float(j);
                    float y = 0.42 + fj*0.075 + sin(p.x*13.0+t*1.6+fj*0.8)*(0.025+bandAt(j*7)*0.10);
                    addGlow(rgb, alpha, rainbow(fj/4.0), lineGlow(abs(p.y-y),0.012)*0.85);
                }
            } else if (uType == 9) {
                float radius = length(uv);
                float angle = atan(uv.y,uv.x);
                float spoke = abs(sin(angle*48.0));
                float energy = bandAt(int(mod(floor((angle+PI)*5.0),32.0)));
                float endR = 0.12 + energy*0.20;
                float mask = step(0.10,radius)*step(radius,endR);
                addGlow(rgb,alpha,rainbow((angle+PI)/(2.0*PI)),mask*lineGlow(spoke,0.055)*0.95);
                addGlow(rgb,alpha,uAccent,lineGlow(radius-0.10-bass*0.04,0.012)*0.65);
            } else if (uType == 10) {
                vec2 q = uv;
                float rot = t*0.22;
                q = mat2(cos(rot),-sin(rot),sin(rot),cos(rot))*q;
                float scale = max(abs(q.x),abs(q.y));
                float pulse = 1.0 + bandAt(5)*0.15;
                float rings = abs(fract(scale*11.0*pulse-t*0.18)-0.5);
                addGlow(rgb,alpha,rainbow(scale*2.5+t*0.03),lineGlow(rings,0.07)*0.85);
                float rays = abs(sin(atan(q.y,q.x)*8.0));
                addGlow(rgb,alpha,uAccent,lineGlow(rays,0.025)*0.18);
            }
            float outAlpha = clamp(alpha,0.0,0.92);
            gl_FragColor = vec4(clamp(rgb,0.0,1.0), outAlpha);
        }
    """.trimIndent()

    private val vertices = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        visualizerOrdinal: Int,
        timeMs: Long,
        bands: FloatArray,
        bass: Float,
        colorHex: String,
        artist: String = "",
        title: String = ""
    ) {
        if (width <= 0 || height <= 0 || visualizerOrdinal == 11) return
        synchronized(lock) {
            val bitmap = try {
                render(width, height, visualizerOrdinal, timeMs, bands, bass, colorHex)
            } catch (_: Throwable) {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                }
                drawFallback(canvas, width, height, visualizerOrdinal, bands, colorHex)
                null
            }
            if (bitmap != null) canvas.drawBitmap(bitmap, 0f, 0f, null)
        }

        // Metadata remains legible and is deliberately drawn after the transparent GPU layer.
        if (visualizerOrdinal == 0) {
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
                textSize = height * 0.078f
                setShadowLayer(height * 0.006f, 0f, 0f, Color.WHITE)
            }
            if (artist.isNotBlank() && !artist.equals("unknown", true)) canvas.drawText(artist, width * 0.5f, height * 0.265f, white)
            if (title.isNotBlank() && !title.equals("unknown", true)) {
                white.textSize = height * 0.052f
                canvas.drawText(title, width * 0.5f, height * 0.315f, white)
            }
        }
    }

    private fun render(
        width: Int,
        height: Int,
        type: Int,
        timeMs: Long,
        bands: FloatArray,
        bass: Float,
        colorHex: String
    ): Bitmap = synchronized(lock) {
        ensureContext(width, height)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Unable to activate OpenGL ES context" }
        if (program == 0) createProgram()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(timeLocation, timeMs / 1000f)
        GLES20.glUniform1i(typeLocation, type)
        GLES20.glUniform1f(bassLocation, bass.coerceIn(0f,1f))
        val color = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.CYAN)
        GLES20.glUniform3f(colorLocation, Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f)
        val values = FloatArray(32) { i -> bands.getOrElse(i) { 0.08f }.coerceIn(0f,1f) }
        GLES20.glUniform1fv(bandsLocation, 32, values, 0)
        val buffer = vertexBuffer ?: ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
            .also { vertexBuffer = it }
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)

        val pixelBytes = width * height * 4
        val pixels = pixelBuffer?.takeIf { it.capacity() == pixelBytes }
            ?: ByteBuffer.allocateDirect(pixelBytes).order(ByteOrder.nativeOrder()).also { pixelBuffer = it }
        pixels.clear()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        val pixelCount = width * height
        val argb = argbBuffer?.takeIf { it.size == pixelCount }
            ?: IntArray(pixelCount).also { argbBuffer = it }
        for (y in 0 until height) {
            val targetY = height - 1 - y
            for (x in 0 until width) {
                val r = pixels.get().toInt() and 0xff
                val g = pixels.get().toInt() and 0xff
                val b = pixels.get().toInt() and 0xff
                val a = pixels.get().toInt() and 0xff
                argb[targetY * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        val output = bitmapCache?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { previous ->
                bitmapCache?.recycle()
                bitmapCache = previous
            }
        output.setPixels(argb,0,width,0,0,width,height)
        output
    }


    private fun drawFallback(canvas: Canvas, width: Int, height: Int, type: Int, bands: FloatArray, colorHex: String) {
        val accent = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.CYAN)
        val count = if (type == 0 || type == 4) 16 else 32
        val gap = width * 0.012f
        val barWidth = (width * 0.86f - gap * (count - 1)) / count
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.FILL }
        val baseY = height * 0.68f
        for (i in 0 until count) {
            val level = bands.getOrElse(i % bands.size.coerceAtLeast(1)) { 0.08f }.coerceIn(0.02f, 1f)
            val barHeight = height * (0.035f + level * 0.24f)
            val left = width * 0.07f + i * (barWidth + gap)
            paint.alpha = 130 + (level * 100).toInt()
            canvas.drawRoundRect(left, baseY - barHeight, left + barWidth, baseY, barWidth * 0.25f, barWidth * 0.25f, paint)
        }
    }

    private fun ensureContext(width: Int, height: Int) {
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) {
            if (surfaceWidth != width || surfaceHeight != height) {
                runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                surface = EGL14.EGL_NO_SURFACE
            } else return
        }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "OpenGL ES display unavailable" }
        if (!displayInitialized) {
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "OpenGL ES initialization failed" }
            displayInitialized = true
        }
        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display,configAttrs,0,configs,0,1,count,0) && count[0] > 0) { "No RGBA OpenGL ES config" }
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE)
        if (context == EGL14.EGL_NO_CONTEXT) {
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,contextAttrs,0)
            check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES context creation failed" }
        }
        val surfaceAttrs = intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE)
        surface = EGL14.eglCreatePbufferSurface(display,configs[0],surfaceAttrs,0)
        check(surface != EGL14.EGL_NO_SURFACE) { "OpenGL ES surface creation failed" }
        surfaceWidth = width
        surfaceHeight = height
    }

    private fun createProgram() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program,vs)
        GLES20.glAttachShader(program,fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0)
        check(status[0] == GLES20.GL_TRUE) { "OpenGL ES program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        positionLocation = GLES20.glGetAttribLocation(program,"aPosition")
        resolutionLocation = GLES20.glGetUniformLocation(program,"uResolution")
        timeLocation = GLES20.glGetUniformLocation(program,"uTime")
        typeLocation = GLES20.glGetUniformLocation(program,"uType")
        bassLocation = GLES20.glGetUniformLocation(program,"uBass")
        colorLocation = GLES20.glGetUniformLocation(program,"uAccent")
        bandsLocation = GLES20.glGetUniformLocation(program,"uBands[0]")
        check(positionLocation >= 0 && resolutionLocation >= 0 && bandsLocation >= 0) { "OpenGL ES shader bindings missing" }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
        check(status[0] == GLES20.GL_TRUE) { "OpenGL ES shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }
}
