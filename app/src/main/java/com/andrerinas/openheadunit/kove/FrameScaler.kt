package com.andrerinas.openheadunit.kove

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Copies decoded frames onto the encoder's input surface.
 *
 * The decoder renders into [inputSurface]. Each new frame is drawn into the target with Android
 * Auto's empty margins cut off, scaled to fit the target. When the remaining picture is within a
 * few percent of the target's shape it simply fills the target, so there are no thin black bars.
 */
internal class FrameScaler(
    private val target: Surface,
    private val targetWidth: Int,
    private val targetHeight: Int
) {
    private val thread = HandlerThread("Kove-Scaler")
    private lateinit var handler: Handler

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texture = 0
    private var aCorner = 0
    private var uDest = 0
    private var uSource = 0
    private var uTexMatrix = 0
    private var uSampler = 0

    private var frames: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    private val texMatrix = FloatArray(16)

    // Source frame size and the margin (total pixels) Android Auto leaves empty in it.
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var marginW = 0
    @Volatile private var marginH = 0

    // Rows of the target covered by the motorcycle's instrument strips: the picture goes between them.
    @Volatile private var insetTop = 0
    @Volatile private var insetBottom = 0
    // Rows of the picture (in target rows) that hang below the bottom edge and are not drawn.
    @Volatile private var cutBottom = 0

    private val corners = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    fun start(): Boolean {
        thread.start()
        handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var ok = false
        handler.post {
            try {
                setUpEgl()
                setUpProgram()
                val st = SurfaceTexture(texture)
                st.setOnFrameAvailableListener({ draw() }, handler)
                frames = st
                inputSurface = Surface(st)
                ok = true
            } catch (e: Exception) {
                KoveLog.e("Scaler GL setup failed: ${e.message}")
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun setSourceSize(width: Int, height: Int) {
        frameW = width
        frameH = height
    }

    fun setMargins(widthMargin: Int, heightMargin: Int) {
        marginW = widthMargin.coerceAtLeast(0)
        marginH = heightMargin.coerceAtLeast(0)
    }

    fun setInsets(top: Int, bottom: Int, cut: Int) {
        insetTop = top.coerceIn(0, targetHeight / 2)
        insetBottom = bottom.coerceIn(0, targetHeight / 2)
        cutBottom = cut.coerceIn(0, targetHeight)
    }

    fun release() {
        if (!thread.isAlive) return
        val done = CountDownLatch(1)
        handler.post {
            runCatching { frames?.release() }
            runCatching { inputSurface?.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            done.countDown()
        }
        runCatching { done.await(2, TimeUnit.SECONDS) }
        thread.quitSafely()
    }

    // ─── Drawing ──────────────────────────────────────────────────

    private fun draw() {
        val st = frames ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // Visible part of the source, in texture coordinates (margins are split evenly).
            val w = frameW
            val h = frameH
            var sx = 0f; var sy = 0f; var sw = 1f; var sh = 1f
            var aspect = targetWidth.toFloat() / targetHeight
            if (w > 0 && h > 0) {
                val mw = marginW.coerceAtMost(w - 1)
                val mh = marginH.coerceAtMost(h - 1)
                sx = mw / 2f / w
                sy = mh / 2f / h
                sw = (w - mw).toFloat() / w
                sh = (h - mh).toFloat() / h
                aspect = (w - mw).toFloat() / (h - mh)
            }

            // Where it goes: the band between the instrument strips, in clip space (y up).
            val top = insetTop
            val bottom = insetBottom
            val bandH = (targetHeight - top - bottom).coerceAtLeast(1)

            // Part of Android Auto laid out below the display edge: leave its bottom rows out.
            // Texture v grows upwards, so the bottom of the picture is the low end of the range.
            val cut = cutBottom
            if (cut > 0) {
                val keep = bandH.toFloat() / (bandH + cut)
                sy += sh * (1f - keep)
                sh *= keep
                aspect /= keep
            }
            val bandAspect = targetWidth.toFloat() / bandH
            var dw = 1f
            var dh = 1f
            // Android Auto is laid out for the shape the rider sees (pixel stretch included), so the
            // picture fills the band; only a clearly different shape gets black bars.
            val shown = aspect / KoveSettings.pixelStretch
            if (kotlin.math.abs(shown / bandAspect - 1f) >= 0.06f) {
                if (shown > bandAspect) dh = bandAspect / shown else dw = shown / bandAspect
            }
            val bandCenter = 1f - 2f * (top + bandH / 2f) / targetHeight
            val bandHalf = bandH.toFloat() / targetHeight

            GLES20.glViewport(0, 0, targetWidth, targetHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform4f(uDest, -dw, bandCenter - dh * bandHalf, 2f * dw, 2f * dh * bandHalf)
            GLES20.glUniform4f(uSource, sx, sy, sw, sh)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(uSampler, 0)
            corners.position(0)
            GLES20.glVertexAttribPointer(aCorner, 2, GLES20.GL_FLOAT, false, 0, corners)
            GLES20.glEnableVertexAttribArray(aCorner)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (KoveSettings.calibrating) outline(top, bandH)

            EGLExt.eglPresentationTimeANDROID(display, surface, st.timestamp)
            EGL14.eglSwapBuffers(display, surface)
        } catch (e: Exception) {
            KoveLog.w("Scaler draw failed: ${e.message}")
        }
    }

    /** Green frame around the visible band, so its edges can be lined up with the TFT's strips. */
    private fun outline(top: Int, bandH: Int) {
        val t = 6
        val y0 = targetHeight - top - bandH  // GL rows count from the bottom
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 1f, 0f, 1f)
        for ((x, y, w, h) in listOf(
            intArrayOf(0, y0 + bandH - t, targetWidth, t),
            intArrayOf(0, y0, targetWidth, t),
            intArrayOf(0, y0, t, bandH),
            intArrayOf(targetWidth - t, y0, t, bandH)
        ).map { listOf(it[0], it[1], it[2], it[3]) }) {
            GLES20.glScissor(x, y, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        // A square for the rider's eye: drawn narrower by the pixel stretch, it looks square on a
        // correctly set TFT and like a rectangle when the width setting is off.
        val side = (targetWidth * 0.45f).toInt()
        val sw = (side / KoveSettings.pixelStretch).toInt()
        val sx = (targetWidth - sw) / 2
        val sy = y0 + (bandH - side) / 2
        for ((x, y, w, h) in listOf(
            intArrayOf(sx, sy + side - t, sw, t),
            intArrayOf(sx, sy, sw, t),
            intArrayOf(sx, sy, t, side),
            intArrayOf(sx + sw - t, sy, t, side)
        ).map { listOf(it[0], it[1], it[2], it[3]) }) {
            GLES20.glScissor(x, y, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    // ─── Setup ────────────────────────────────────────────────────

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val recordable = 0x3142 // EGL_RECORDABLE_ANDROID: the config must feed a video encoder
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            recordable, 1,
            EGL14.EGL_NONE
        )
        val found = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, wanted, 0, found, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        val config = found[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        surface = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
    }

    private fun setUpProgram() {
        // A unit square: each corner is placed into the destination rectangle and sampled from the
        // source rectangle, then the SurfaceTexture matrix maps it into the decoded buffer.
        val vertex = """
            attribute vec2 aCorner;
            uniform vec4 uDest;
            uniform vec4 uSource;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(uDest.xy + aCorner * uDest.zw, 0.0, 1.0);
                vTex = (uTexMatrix * vec4(uSource.xy + aCorner * uSource.zw, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uSampler;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uSampler, vTex); }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "shader link: ${GLES20.glGetProgramInfoLog(program)}" }
        aCorner = GLES20.glGetAttribLocation(program, "aCorner")
        uDest = GLES20.glGetUniformLocation(program, "uDest")
        uSource = GLES20.glGetUniformLocation(program, "uSource")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        for ((name, value) in listOf(
            GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR,
            GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
            GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE,
            GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE
        )) GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name, value)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader compile: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }
}
