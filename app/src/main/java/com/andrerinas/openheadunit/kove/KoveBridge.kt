package com.andrerinas.openheadunit.kove

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import java.net.Inet4Address

/**
 * Shows the running Android Auto session on the Kove 800X TFT.
 *
 * Open Headunit receives Android Auto's video; [onVideo] taps that stream, [KoveTranscoder] turns
 * it into the TFT's 600x1024 H.264, and [TftLink] / [TftBle] carry it to the motorcycle.
 * Lives exactly as long as the Android Auto session.
 */
object KoveBridge {

    const val TFT_WIDTH = 600
    const val TFT_HEIGHT = 1024

    /** The TFT's hotspot hands out addresses in this range. */
    private const val TFT_SUBNET = "192.168.10."

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var active = false
    @Volatile private var transcoder: KoveTranscoder? = null

    private var context: Context? = null
    private var link: TftLink? = null
    private var ble: TftBle? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var tftWifiCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var unitsIn = 0L
    private var focusRequests = 0

    /** True while an Android Auto session is being shown on the TFT. */
    val isActive: Boolean get() = active

    // ─── Session lifecycle ────────────────────────────────────────

    /** Called when the Android Auto session is up. */
    fun start(ctx: Context) {
        synchronized(lock) {
            if (active) return
            active = true
        }
        val app = ctx.applicationContext
        context = app
        KoveLog.open(app)
        KoveLog.i("Bridge starting (Android Auto session connected)")
        unitsIn = 0
        focusRequests = 0

        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KoveBridge::cpu").also { it.acquire() }
        } catch (e: Exception) {
            KoveLog.w("Wake lock failed: ${e.message}")
        }

        val mac = TftBle.findPairedTft()
        if (mac != null) {
            ble = TftBle(
                app,
                onLinked = {
                    // An already paired TFT switches its hotspot on without announcing anything, so
                    // the Bluetooth link alone is the cue. A phone sitting on another Wi-Fi (home)
                    // never moves to it by itself: ask Android for it if we are not on it shortly.
                    main.removeCallbacks(joinTftWifi)
                    main.postDelayed(joinTftWifi, 8_000L)
                },
                onReady = {
                    KoveLog.i("TFT paired and ready")
                    main.removeCallbacks(joinTftWifi)
                    main.postDelayed(joinTftWifi, 5_000L)
                }
            ).also { it.connect(mac) }
        } else {
            KoveLog.w("No paired Kove TFT (Bluetooth name CQKY...) - is Bluetooth on?")
        }
        followWifi(app)
        logScreenChanges(app)
        main.postDelayed(focusRequester, 4_000L)
        main.postDelayed(logPublisher, 5_000L)
    }

    /** Called when the Android Auto session ends. */
    fun stop() {
        synchronized(lock) {
            if (!active) return
            active = false
        }
        KoveLog.i("Bridge stopping")
        VehicleData.clear()
        main.removeCallbacks(focusRequester)
        main.removeCallbacks(logPublisher)
        ble?.disconnect()
        ble = null
        stopWifiFollow()
        link?.stop()
        link = null
        transcoder?.release()
        transcoder = null
        screenReceiver?.let { r -> runCatching { context?.unregisterReceiver(r) } }
        screenReceiver = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        context?.let { publishLog(it) }
    }

    // ─── Video ────────────────────────────────────────────────────

    /**
     * One Android Auto video access unit, exactly as Open Headunit hands it to its own decoder.
     * Runs on the transport's read thread, so it only queues work.
     */
    fun onVideo(buf: ByteArray, offset: Int, size: Int) {
        if (!active || size < 5) return
        val t = transcoder ?: createTranscoder(buf, offset, size) ?: return
        unitsIn++
        if (unitsIn == 1L || unitsIn % 2700L == 0L) {
            KoveLog.i("Video units from Android Auto: $unitsIn, frames to TFT: ${t.framesOut}")
        }
        t.feed(buf, offset, size)
    }

    /** The decoder can only start on a parameter set, so units before the first one are skipped. */
    private fun createTranscoder(buf: ByteArray, offset: Int, size: Int): KoveTranscoder? {
        val mime = codecOf(buf, offset, size) ?: return null
        synchronized(lock) {
            transcoder?.let { return it }
            if (!active) return null
            val t = KoveTranscoder(mime, TFT_WIDTH, TFT_HEIGHT) { chunk -> link?.sendVideo(chunk) }
            if (!t.start()) return null
            val mw = HeadUnitScreenConfig.getWidthMargin()
            val mh = HeadUnitScreenConfig.getHeightMargin()
            t.setSourceMargins(mw, mh)
            t.setInsets(KoveSettings.hiddenTop, KoveSettings.coveredBottom, KoveSettings.cutBottom)
            KoveLog.i("Transcoder started: $mime, Android Auto margins w=$mw h=$mh, strips ${KoveSettings.hiddenTop}/${KoveSettings.hiddenBottom}, width ${KoveSettings.widthPercent}%")
            transcoder = t
            return t
        }
    }

    /** H.264 (sequence parameter set) or H.265 (video/sequence parameter set) in an Annex-B buffer. */
    private fun codecOf(b: ByteArray, offset: Int, size: Int): String? {
        var i = offset
        val end = offset + size - 4
        while (i < end) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val header = b[i + 3].toInt() and 0xFF
                if (header and 0x80 == 0 && header and 0x1F == 7) return "video/avc"
                val hevcType = (header shr 1) and 0x3F
                if (hevcType == 32 || hevcType == 33) return "video/hevc"
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    /**
     * Android Auto only sends video to a head unit holding video focus, which Open Headunit takes
     * when its projection screen appears. With the phone locked that screen may never appear, so
     * ask from here until video arrives.
     */
    private val focusRequester = object : Runnable {
        override fun run() {
            if (!active || unitsIn > 0 || focusRequests >= 10) return
            focusRequests++
            val ctx = context ?: return
            try {
                App.provide(ctx).commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
                KoveLog.i("No video yet - asking Android Auto for video (attempt $focusRequests)")
            } catch (e: Exception) {
                KoveLog.w("Video focus request failed: ${e.message}")
            }
            main.postDelayed(this, 3_000L)
        }
    }

    // ─── TFT link over the motorcycle's Wi-Fi ─────────────────────

    private fun startLink(ip: String) {
        link?.stop()
        val l = TftLink(
            bindIp = ip,
            width = TFT_WIDTH,
            height = TFT_HEIGHT,
            onVideoConnected = {
                KoveLog.i("TFT video connected - sending Android Auto")
                transcoder?.requestKeyFrame()
            },
            onVideoDisconnected = {
                // A TFT that dropped the video port comes back after the Bluetooth hello.
                if (active) main.postDelayed({ ble?.wake() }, 2000)
            }
        )
        link = l
        l.start()
    }

    /** Waits for the motorcycle's Wi-Fi, routes this process over it and (re)starts the TFT link. */
    private fun followWifi(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback(
            if (android.os.Build.VERSION.SDK_INT >= 31) FLAG_INCLUDE_LOCATION_INFO else 0
        ) {
            override fun onAvailable(network: Network) = onWifi(cm, network)

            override fun onLost(network: Network) {
                main.post { dropIfBound(cm, network, "Wi-Fi lost") }
            }
        }
        wifiCallback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            KoveLog.e("Cannot watch Wi-Fi: ${e.message}")
        }
    }

    /** Starts the TFT link if [network] is the motorcycle's Wi-Fi. */
    private fun onWifi(cm: ConnectivityManager, network: Network) {
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address }
            ?.hostAddress
        main.post {
            if (!active) return@post
            if (ip == null || !ip.startsWith(TFT_SUBNET)) {
                KoveLog.w("Wi-Fi $ip is not the motorcycle's - waiting")
                return@post
            }
            if (link != null && boundNetwork == network) return@post
            context?.let { rememberSsid(it, cm, network) }
            // The TFT's hotspot has no internet; send this process's traffic through it anyway.
            runCatching { cm.bindProcessToNetwork(network) }
            boundNetwork = network
            KoveLog.i("On the motorcycle Wi-Fi ($ip)")
            startLink(ip)
        }
    }

    private fun dropIfBound(cm: ConnectivityManager, network: Network, why: String) {
        KoveLog.w(why)
        if (boundNetwork != null && boundNetwork != network) return
        runCatching { cm.bindProcessToNetwork(null) }
        link?.stop()
        link = null
        boundNetwork = null
    }

    /**
     * Asks Android to join the motorcycle's Wi-Fi next to (or instead of) the current one. The first
     * time Android shows a list and the user picks the motorcycle; its name is remembered, and from
     * then on Android connects to that name without asking.
     */
    private val joinTftWifi = Runnable {
        try {
            requestTftWifi()
        } catch (e: Exception) {
            tftWifiCallback = null
            KoveLog.e("Motorcycle Wi-Fi request failed: ${e.message}")
        }
    }

    enum class JoinResult { REQUESTED, ALREADY_CONNECTED, NO_SESSION, NAME_UNKNOWN }

    /**
     * Asks Android for the motorcycle Wi-Fi now, from a screen the user is looking at: Android only
     * shows its approval dialog for an app in the foreground, which the automatic attempt may not be.
     */
    fun joinTftWifiNow(): JoinResult {
        val ctx = context ?: return JoinResult.NO_SESSION
        if (!active) return JoinResult.NO_SESSION
        if (link != null) return JoinResult.ALREADY_CONNECTED
        if (KoveSettings.tftSsid(ctx) == null) return JoinResult.NAME_UNKNOWN
        tftWifiCallback?.let { cb ->
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        tftWifiCallback = null
        joinTftWifi.run()
        return JoinResult.REQUESTED
    }

    private fun requestTftWifi() {
        val ctx = context ?: return
        if (!active || link != null || tftWifiCallback != null) return
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val known = KoveSettings.tftSsid(ctx)
        if (known == null) {
            KoveLog.w("Motorcycle Wi-Fi name not known yet - connect to it by hand once and it will be remembered")
            return
        }
        val spec = try {
            android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(known).build()
        } catch (e: Exception) {
            KoveLog.e("Bad motorcycle Wi-Fi name '$known': ${e.message}")
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                rememberSsid(ctx, cm, network)
                onWifi(cm, network)
            }

            override fun onUnavailable() {
                main.post {
                    KoveLog.w("Motorcycle Wi-Fi was not joined (not chosen or not in range)")
                    tftWifiCallback = null
                }
            }

            override fun onLost(network: Network) {
                main.post { dropIfBound(cm, network, "Motorcycle Wi-Fi lost") }
            }
        }
        tftWifiCallback = cb
        KoveLog.i("Joining the motorcycle Wi-Fi '$known'")
        try {
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            tftWifiCallback = null
            KoveLog.e("Cannot request the motorcycle Wi-Fi: ${e.message}")
        }
    }

    private fun rememberSsid(ctx: Context, cm: ConnectivityManager, network: Network) {
        val fromNetwork = if (android.os.Build.VERSION.SDK_INT >= 29) {
            (cm.getNetworkCapabilities(network)?.transportInfo as? android.net.wifi.WifiInfo)?.ssid
        } else null
        @Suppress("DEPRECATION")
        val fromManager = runCatching {
            (ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid
        }.getOrNull()
        val ssid = listOf(fromNetwork, fromManager)
            .map { it?.trim('"') }
            .firstOrNull { !it.isNullOrEmpty() && it != "<unknown ssid>" }
        if (ssid == null) {
            KoveLog.w("Motorcycle Wi-Fi name is hidden by Android (location permission/off?) - cannot remember it")
            return
        }
        if (ssid != KoveSettings.tftSsid(ctx)) {
            KoveSettings.saveTftSsid(ctx, ssid)
            KoveLog.i("Remembered the motorcycle Wi-Fi: $ssid")
        }
    }

    private fun stopWifiFollow() {
        val ctx = context ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        wifiCallback = null
        tftWifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        tftWifiCallback = null
        boundNetwork = null
        main.removeCallbacks(joinTftWifi)
        runCatching { cm.bindProcessToNetwork(null) }
    }

    /**
     * Applies new instrument-strip heights to a running session: Android Auto is asked to lay out in
     * the new visible area and the picture is placed in it.
     */
    fun applyTftInsets() {
        val t = transcoder ?: return
        val mw = HeadUnitScreenConfig.getWidthMargin()
        val mh = HeadUnitScreenConfig.getHeightMargin()
        t.setSourceMargins(mw, mh)
        t.setInsets(KoveSettings.hiddenTop, KoveSettings.coveredBottom, KoveSettings.cutBottom)
        val ctx = context ?: return
        try {
            val announced = HeadUnitScreenConfig.onMarginsDiverged?.invoke() ?: false
            if (!announced) {
                App.provide(ctx).commManager.sendUpdateUiConfigRequest(
                    HeadUnitScreenConfig.getLeftMargin(), HeadUnitScreenConfig.getTopMargin(),
                    HeadUnitScreenConfig.getRightMargin(), HeadUnitScreenConfig.getBottomMargin()
                )
                HeadUnitScreenConfig.recordAnnouncedMargins(mw, mh)
            }
            KoveLog.i("TFT strips: top=${KoveSettings.hiddenTop} bottom=${KoveSettings.hiddenBottom} width=${KoveSettings.widthPercent}%, AA margins w=$mw h=$mh")
        } catch (e: Exception) {
            KoveLog.w("Could not update Android Auto layout: ${e.message}")
        }
    }

    // ─── Diagnostics ──────────────────────────────────────────────

    /** Logs screen on/off with the frame counters, so a log shows whether Android Auto kept sending. */
    private fun logScreenChanges(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val what = intent?.action?.substringAfterLast('.') ?: return
                KoveLog.i("$what - video units: $unitsIn, frames to TFT: ${transcoder?.framesOut ?: 0}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(r, filter)
            }
            screenReceiver = r
        } catch (e: Exception) {
            KoveLog.w("Screen log unavailable: ${e.message}")
        }
    }

    /** Keeps a readable copy of the log in Downloads/Kove while the bridge runs. */
    private var lastInteractive: Boolean? = null

    private val logPublisher = object : Runnable {
        override fun run() {
            context?.let { ctx ->
                val on = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                if (on != lastInteractive) {
                    KoveLog.i("Phone screen ${if (on) "on" else "off"} - video units: $unitsIn, frames to TFT: ${transcoder?.framesOut ?: 0}")
                    lastInteractive = on
                }
                publishLog(ctx)
            }
            if (active) main.postDelayed(this, 15_000L)
        }
    }

    private fun publishLog(ctx: Context) {
        Thread { KoveLogPublisher.publish(ctx) }.start()
    }
}
