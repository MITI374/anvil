package com.andrerinas.openheadunit.kove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A small pill with range and fuel consumption, drawn over the picture on the TFT.
 * Lives on the scaler's GL thread: every call must come from there.
 */
internal class BadgeLayer {

    private var program = 0
    private var aCorner = 0
    private var uDest = 0
    private var uSampler = 0
    private var texture = 0
    private var shownVersion = Int.MIN_VALUE
    private var bitmapW = 0
    private var bitmapH = 0
    private var hasText = false

    private val corners = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    fun setUp() {
        val vertex = """
            attribute vec2 aCorner;
            uniform vec4 uDest;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(uDest.xy + aCorner * uDest.zw, 0.0, 1.0);
                vTex = vec2(aCorner.x, 1.0 - aCorner.y);
            }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            uniform sampler2D uSampler;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uSampler, vTex); }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        aCorner = GLES20.glGetAttribLocation(program, "aCorner")
        uDest = GLES20.glGetUniformLocation(program, "uDest")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * Draws the badge in [corner] (1 top-left, 2 top-right, 3 bottom-left, 4 bottom-right) of the
     * band [bandTop]..[bandTop]+[bandH] (rows counted from the top of the target).
     */
    fun draw(targetW: Int, targetH: Int, bandTop: Int, bandH: Int, corner: Int) {
        if (corner !in 1..4 || program == 0) return
        refreshBitmap()
        if (!hasText) return

        val margin = 12
        val x = if (corner == 1 || corner == 3) margin else targetW - margin - bitmapW
        val yTop = if (corner == 1 || corner == 2) bandTop + margin else bandTop + bandH - margin - bitmapH
        // Clip space, y up.
        val left = -1f + 2f * x / targetW
        val bottom = 1f - 2f * (yTop + bitmapH) / targetH
        val w = 2f * bitmapW / targetW
        val h = 2f * bitmapH / targetH

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniform4f(uDest, left, bottom, w, h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uSampler, 0)
        corners.position(0)
        GLES20.glVertexAttribPointer(aCorner, 2, GLES20.GL_FLOAT, false, 0, corners)
        GLES20.glEnableVertexAttribArray(aCorner)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Re-renders the text into the texture only when the trip data changed. */
    private fun refreshBitmap() {
        val size = KoveSettings.badgeTextSize
        val version = (VehicleData.version * 2 + if (KoveSettings.calibrating) 1 else 0) * 128 + size
        if (version == shownVersion) return
        shownVersion = version
        var lines = VehicleData.badgeLines()
        if (lines.isEmpty() && KoveSettings.calibrating) lines = listOf("-.- l/100 km", "--- km")
        hasText = lines.isNotEmpty()
        if (!hasText) return

        // First line (consumption) full size, second (range) a little smaller.
        val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size.toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val second = Paint(main).apply {
            textSize = size * 0.8f
            color = Color.rgb(255, 200, 120)
        }
        val paints = lines.indices.map { if (it == 0) main else second }
        val texts = lines.mapIndexed { i, t -> if (i == 0) "\u26FD $t" else t }
        val padX = size * 0.5f
        val padY = size * 0.25f
        val gap = size * 0.15f
        val heights = paints.map { p -> p.fontMetrics.let { it.descent - it.ascent } }
        bitmapW = (texts.indices.maxOf { paints[it].measureText(texts[it]) } + 2 * padX).toInt()
        bitmapH = (heights.sum() + gap * (texts.size - 1) + 2 * padY).toInt()
        val bmp = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 16, 20, 24) }
        canvas.drawRoundRect(RectF(0f, 0f, bitmapW.toFloat(), bitmapH.toFloat()), size * 0.5f, size * 0.5f, bg)
        var y = padY
        for (i in texts.indices) {
            canvas.drawText(texts[i], padX, y - paints[i].fontMetrics.ascent, paints[i])
            y += heights[i] + gap
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
