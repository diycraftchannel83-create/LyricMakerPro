package com.winsmediatama.lyricmakerpro.visualizer.opengl

import android.app.ActivityManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import com.winsmediatama.lyricmakerpro.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.HandlerThread

/**
 * Transparent OpenGL ES 3.0 spectrum layer for the editor preview.
 * TextureView keeps Compose lyrics and editor controls composited above the GPU output.
 */
class GLSpectrumVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), SurfaceTextureListener {

    @Volatile private var renderHandler: Handler? = null
    @Volatile private var renderThread: HandlerThread? = null
    @Volatile private var frameTimeMs = 0L
    @Volatile private var frameBands = FloatArray(16) { 0.06f }
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var timeLocation = -1
    private var bandsLocation = -1
    private var positionLocation = -1
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(12 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f, 3f,-1f, -1f,3f))
            position(0)
        }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun updateFrame(timeMs: Long, bands: FloatArray) {
        frameTimeMs = timeMs
        frameBands = FloatArray(16) { index ->
            (bands.getOrNull(index) ?: 0.04f).coerceIn(0f, 1f)
        }
        renderHandler?.post { drawFrame() }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        val thread = HandlerThread("LyricMaker-GLSpectrum").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler
        handler.post {
            try {
                initializeEgl(surface)
                drawFrame()
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to initialize OpenGL ES spectrum preview", error)
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        renderHandler?.post {
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            drawFrame()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val handler = renderHandler
        val thread = renderThread
        if (handler != null && thread != null) {
            val latch = CountDownLatch(1)
            handler.post {
                releaseEgl()
                latch.countDown()
            }
            runCatching { latch.await(800, TimeUnit.MILLISECONDS) }
            thread.quitSafely()
        }
        renderHandler = null
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun initializeEgl(surfaceTexture: SurfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "EGL initialize failed" }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No RGBA8 OpenGL ES 3 EGL config"
        }
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL window surface creation failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "EGL make-current failed"
        }

        program = linkProgram(readRaw(R.raw.lyric_gl_spectrum_vertex), readRaw(R.raw.lyric_gl_spectrum_fragment))
        timeLocation = GLES30.glGetUniformLocation(program, "uTime")
        bandsLocation = GLES30.glGetUniformLocation(program, "uBands")
        positionLocation = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
    }

    private fun drawFrame() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE ||
            eglContext == EGL14.EGL_NO_CONTEXT || program == 0
        ) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(timeLocation, frameTimeMs / 1000f)
        GLES30.glUniform1fv(bandsLocation, 16, frameBands, 0)
        vertices.position(0)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisableVertexAttribArray(positionLocation)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            }
            if (program != 0) GLES30.glDeleteProgram(program)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        program = 0
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun readRaw(id: Int): String =
        context.resources.openRawResource(id).use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "OpenGL shader compilation failed: " + GLES30.glGetShaderInfoLog(shader)
        }
        return shader
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES30.glCreateProgram()
        GLES30.glAttachShader(result, vertex)
        GLES30.glAttachShader(result, fragment)
        GLES30.glBindAttribLocation(result, 0, "aPosition")
        GLES30.glLinkProgram(result)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "OpenGL program link failed: " + GLES30.glGetProgramInfoLog(result)
        }
        return result
    }

    companion object {
        private const val TAG = "GLSpectrumPreview"
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

        fun isSupported(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return (manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x00030000
        }
    }
}