package com.andrerinas.openheadunit.kove

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns the Android Auto video stream into the stream the Kove TFT expects.
 *
 * Android Auto video (H.264 or H.265, any size) -> hardware decoder -> GL letterbox -> H.264 encoder
 * at [outWidth]x[outHeight]. Nothing here touches the phone screen, so it keeps running with the
 * screen off as long as Android Auto keeps sending frames.
 */
class KoveTranscoder(
    private val mime: String,
    private val outWidth: Int = 600,
    private val outHeight: Int = 1024,
    private val fps: Int = 30,
    private val onNal: (ByteArray) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val input = ArrayBlockingQueue<ByteArray>(120)

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var decoder: MediaCodec? = null
    private var scaler: FrameScaler? = null

    private var feedThread: Thread? = null
    private var decoderDrainThread: Thread? = null
    private var encoderDrainThread: Thread? = null

    @Volatile var framesOut = 0L
        private set

    fun start(): Boolean {
        return try {
            val enc = MediaCodec.createEncoderByType("video/avc")
            enc.configure(encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()
            encoder = enc
            encoderSurface = encSurface

            val gl = FrameScaler(encSurface, outWidth, outHeight)
            if (!gl.start()) throw IllegalStateException("GL init failed")
            scaler = gl

            val dec = MediaCodec.createDecoderByType(mime)
            // The real size comes from the stream itself; this is only a starting point.
            val fmt = MediaFormat.createVideoFormat(mime, 1280, 720).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                setInteger(MediaFormat.KEY_MAX_WIDTH, 2560)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, 2560)
            }
            dec.configure(fmt, gl.inputSurface, null, 0)
            dec.start()
            decoder = dec

            running.set(true)
            feedThread = Thread({ feedLoop(dec) }, "Kove-DecFeed").also { it.isDaemon = true; it.start() }
            decoderDrainThread = Thread({ decoderDrainLoop(dec, gl) }, "Kove-DecDrain").also { it.isDaemon = true; it.start() }
            encoderDrainThread = Thread({ encoderDrainLoop(enc) }, "Kove-EncDrain").also { it.isDaemon = true; it.start() }
            KoveLog.i("✅ KoveTranscoder started ($mime -> H.264 ${outWidth}x$outHeight)")
            true
        } catch (e: Exception) {
            KoveLog.e("❌ KoveTranscoder start failed: ${e.message}")
            release()
            false
        }
    }

    private fun encoderFormat() = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, "video/avc")
        setInteger(MediaFormat.KEY_WIDTH, outWidth)
        setInteger(MediaFormat.KEY_HEIGHT, outHeight)
        setInteger(MediaFormat.KEY_BIT_RATE, outWidth * outHeight * 3)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // The TFT drops the link when frames stop, so keep repeating the last picture on a still map.
        setLong("repeat-previous-frame-after", 100_000L)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
    }

    /** Queues one access unit from Android Auto. The bytes are copied; the caller may reuse its buffer. */
    fun feed(buf: ByteArray, offset: Int, size: Int) {
        if (!running.get() || size <= 0) return
        val copy = buf.copyOfRange(offset, offset + size)
        if (!input.offer(copy)) {
            // Decoder is behind: dropping would corrupt the picture until the next key frame, so make room.
            input.poll()
            input.offer(copy)
        }
    }

    /** Tells the scaler which border of the Android Auto frame is margin (cut off before scaling). */
    fun setSourceMargins(widthMargin: Int, heightMargin: Int) {
        scaler?.setMargins(widthMargin, heightMargin)
    }

    /** Rows of the TFT picture hidden under the motorcycle's strips, and Android Auto rows cut off below. */
    fun setInsets(top: Int, bottom: Int, cut: Int) {
        scaler?.setInsets(top, bottom, cut)
    }

    /** Asks the encoder for a key frame right away (a TFT that just connected needs one to start). */
    fun requestKeyFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    private fun feedLoop(dec: MediaCodec) {
        while (running.get()) {
            try {
                val data = input.poll(200, TimeUnit.MILLISECONDS) ?: continue
                var queued = false
                while (running.get() && !queued) {
                    val idx = dec.dequeueInputBuffer(20_000L)
                    if (idx < 0) continue
                    val ib = dec.getInputBuffer(idx)
                    if (ib == null || ib.capacity() < data.size) {
                        dec.queueInputBuffer(idx, 0, 0, 0, 0)
                        KoveLog.w("⚠️ KoveTranscoder: access unit too large (${data.size} B), dropped")
                        break
                    }
                    ib.clear()
                    ib.put(data)
                    dec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
                    queued = true
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) KoveLog.e("❌ KoveTranscoder feed error: ${e.message}")
                break
            }
        }
    }

    private fun decoderDrainLoop(dec: MediaCodec, gl: FrameScaler) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val idx = dec.dequeueOutputBuffer(info, 20_000L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        var w = f.getInteger(MediaFormat.KEY_WIDTH)
                        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        }
                        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                        }
                        gl.setSourceSize(w, h)
                        KoveLog.i("🎬 Android Auto video: ${w}x$h")
                    }
                    idx >= 0 -> dec.releaseOutputBuffer(idx, info.size > 0)
                }
            } catch (e: Exception) {
                if (running.get()) KoveLog.e("❌ KoveTranscoder decoder error: ${e.message}")
                break
            }
        }
    }

    private fun encoderDrainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val idx = enc.dequeueOutputBuffer(info, 20_000L)
                if (idx >= 0) {
                    val ob = enc.getOutputBuffer(idx)
                    if (ob != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(data)
                        enc.releaseOutputBuffer(idx, false)
                        framesOut++
                        onNal(data)
                    } else {
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) KoveLog.e("❌ KoveTranscoder encoder error: ${e.message}")
                break
            }
        }
    }

    fun release() {
        running.set(false)
        for (t in listOf(feedThread, decoderDrainThread, encoderDrainThread)) {
            try { t?.interrupt(); t?.join(500) } catch (_: Exception) {}
        }
        feedThread = null; decoderDrainThread = null; encoderDrainThread = null
        input.clear()
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { scaler?.release() } catch (_: Exception) {}
        scaler = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
    }
}
