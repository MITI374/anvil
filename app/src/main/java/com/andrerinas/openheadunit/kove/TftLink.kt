package com.andrerinas.openheadunit.kove

import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone side of the Kove TFT's Wi-Fi link. The TFT connects to the phone on three ports:
 *  - 15456 video: phone sends a 69-byte size header, then raw H.264, plus a keep-alive every 2 s;
 *  - 17818 control: phone asks for the TFT's identity, answers keep-alives, sends a greeting;
 *  - 15457 keep-alive: phone sends a keep-alive every 200 ms.
 */
class TftLink(
    private val bindIp: String,
    private val width: Int,
    private val height: Int,
    private val onVideoConnected: () -> Unit,
    private val onVideoDisconnected: () -> Unit
) {
    companion object {
        const val PORT_VIDEO = 15456
        const val PORT_CONTROL = 17818
        const val PORT_KEEP_ALIVE = 15457
        private const val ACCEPT_TIMEOUT_MS = 2000
    }

    private val running = AtomicBoolean(false)
    private val threads = ArrayList<Thread>()
    private val servers = ArrayList<ServerSocket>()
    private val clients = ArrayList<Socket>()

    private val videoLock = Any()
    @Volatile private var videoOut: OutputStream? = null

    val isVideoConnected: Boolean get() = videoOut != null

    fun start() {
        if (running.getAndSet(true)) return
        listen("video", PORT_VIDEO, ::serveVideo)
        listen("control", PORT_CONTROL, ::serveControl)
        listen("keep-alive", PORT_KEEP_ALIVE, ::serveKeepAlive)
        KoveLog.i("TFT link listening on $bindIp")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        synchronized(servers) {
            servers.forEach { runCatching { it.close() } }
            servers.clear()
        }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        synchronized(threads) {
            threads.forEach { it.interrupt() }
            threads.clear()
        }
        KoveLog.i("TFT link stopped")
    }

    /** Sends one H.264 chunk to the TFT. Returns false when no TFT is connected. */
    fun sendVideo(data: ByteArray): Boolean = synchronized(videoLock) {
        val out = videoOut ?: return false
        try {
            out.write(data)
            out.flush()
            true
        } catch (e: IOException) {
            KoveLog.w("TFT video write failed: ${e.message}")
            videoOut = null
            false
        }
    }

    // ─── Listening ────────────────────────────────────────────────

    /**
     * Accepts one client at a time on [port]. A server socket that dies (Wi-Fi lost) is rebound
     * instead of being retried forever.
     */
    private fun listen(name: String, port: Int, serve: (Socket) -> Unit) {
        val t = Thread({
            while (running.get()) {
                val server = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        soTimeout = ACCEPT_TIMEOUT_MS
                        bind(InetSocketAddress(bindIp, port))
                    }
                } catch (e: IOException) {
                    KoveLog.w("TFT $name port $port: bind failed (${e.message}), retrying")
                    if (!pause(2000)) return@Thread
                    continue
                }
                synchronized(servers) { servers += server }
                try {
                    while (running.get()) {
                        val client = try {
                            server.accept()
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        KoveLog.i("TFT connected on $name port from ${client.inetAddress.hostAddress}")
                        synchronized(clients) { clients += client }
                        try {
                            serve(client)
                        } catch (e: Exception) {
                            if (running.get()) KoveLog.w("TFT $name connection ended: ${e.message}")
                        } finally {
                            runCatching { client.close() }
                            synchronized(clients) { clients -= client }
                        }
                    }
                } catch (e: IOException) {
                    if (running.get()) KoveLog.w("TFT $name port $port closed (${e.message}), rebinding")
                } finally {
                    runCatching { server.close() }
                    synchronized(servers) { servers -= server }
                }
                if (!pause(1000)) return@Thread
            }
        }, "Kove-$name")
        t.isDaemon = true
        synchronized(threads) { threads += t }
        t.start()
    }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        running.get()
    } catch (_: InterruptedException) {
        false
    }

    // ─── Video (15456) ────────────────────────────────────────────

    private fun serveVideo(socket: Socket) {
        val out = socket.getOutputStream()
        synchronized(videoLock) {
            out.write(TftPacket.videoHeader(width, height))
            out.flush()
            videoOut = out
        }
        onVideoConnected()
        try {
            val input = socket.getInputStream()
            val sink = ByteArray(1024)
            socket.soTimeout = 2000
            var lastKeepAlive = System.currentTimeMillis()
            while (running.get() && videoOut === out) {
                try {
                    if (input.read(sink) < 0) break  // TFT closed the connection
                } catch (_: SocketTimeoutException) {
                }
                val now = System.currentTimeMillis()
                if (now - lastKeepAlive >= 2000) {
                    synchronized(videoLock) {
                        if (videoOut === out) {
                            out.write(TftPacket.KEEP_ALIVE)
                            out.flush()
                        }
                    }
                    lastKeepAlive = now
                }
            }
        } finally {
            synchronized(videoLock) { if (videoOut === out) videoOut = null }
            KoveLog.w("TFT video disconnected")
            onVideoDisconnected()
        }
    }

    // ─── Control (17818) ──────────────────────────────────────────

    private fun serveControl(socket: Socket) {
        val out = socket.getOutputStream()
        out.write(TftPacket.jsonFrame("""{"msg_id":27,"func":"TUC","act":"GET"}"""))
        out.flush()
        val input = socket.getInputStream()
        val reader = TftPacket.Reader()
        val buf = ByteArray(4096)
        var greeted = false
        while (running.get()) {
            val n = input.read(buf)
            if (n < 0) break
            for (msg in reader.feed(buf, n)) {
                when (msg) {
                    is TftPacket.Incoming.KeepAlive -> out.write(TftPacket.KEEP_ALIVE)
                    is TftPacket.Incoming.Json -> KoveLog.i("TFT control: ${msg.text.take(200)}")
                    is TftPacket.Incoming.Binary -> Unit
                }
                if (!greeted) {
                    // The TFT has answered: complete the handshake once.
                    greeted = true
                    TftPacket.controlGreeting().forEach { out.write(it) }
                    KoveLog.i("TFT control handshake sent")
                }
            }
            out.flush()
        }
        KoveLog.w("TFT control disconnected")
    }

    // ─── Keep-alive (15457) ───────────────────────────────────────

    private fun serveKeepAlive(socket: Socket) {
        val out = socket.getOutputStream()
        while (running.get() && !socket.isClosed) {
            out.write(TftPacket.KEEP_ALIVE)
            out.flush()
            Thread.sleep(200)
        }
    }
}
