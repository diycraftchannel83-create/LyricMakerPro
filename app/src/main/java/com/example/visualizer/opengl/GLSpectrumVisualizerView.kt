package com.winsmediatama.lyricmakerpro.visualizer.opengl

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.winsmediatama.lyricmakerpro.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 transparent visualizer surface used by the editor preview.
 * The shader renders only the overlay; the Compose/video background remains visible.
 */
class GLSpectrumVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val spectrumRenderer = SpectrumRenderer(context)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(spectrumRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setPreserveEGLContextOnPause(true)
    }

    fun updateFrame(timeMs: Long, bands: FloatArray) {
        spectrumRenderer.update(timeMs, bands)
        requestRender()
    }

    private class SpectrumRenderer(private val context: Context) : Renderer {
        @Volatile private var frameTimeMs = 0L
        @Volatile private var frameBands = FloatArray(16) { 0.08f }
        private var program = 0
        private var resolutionLocation = -1
        private var timeLocation = -1
        private var bandsLocation = -1
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(3 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
                position(0)
            }

        fun update(timeMs: Long, bands: FloatArray) {
            frameTimeMs = timeMs
            frameBands = FloatArray(16) { index ->
                (bands.getOrNull(index) ?: 0.04f).coerceIn(0f, 1f)
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            val vertex = readRaw(R.raw.lyric_gl_spectrum)
            val fragment = readRaw(R.raw.lyric_gl_spectrum_frag)
            program = linkProgram(vertex, fragment)
            resolutionLocation = GLES30.glGetUniformLocation(program, "uResolution")
            timeLocation = GLES30.glGetUniformLocation(program, "uTime")
            bandsLocation = GLES30.glGetUniformLocation(program, "uBands")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            if (program != 0) GLES30.glUseProgram(program).also {
                GLES30.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (program == 0) return
            GLES30.glUseProgram(program)
            GLES30.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
            GLES30.glUniform1f(timeLocation, frameTimeMs / 1000f)
            GLES30.glUniform1fv(bandsLocation, 16, frameBands, 0)
            vertices.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, vertices)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glDisableVertexAttribArray(0)
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
    }
}