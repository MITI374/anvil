package com.andrerinas.openheadunit.kove

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Bluetooth LE side of the Kove TFT. The TFT only switches its Wi-Fi hotspot on and connects to
 * the phone's TCP ports after this handshake, and it expects a status message every 5 seconds.
 *
 * Every message is a JSON object written to the TFT's write characteristic; the TFT answers on its
 * notify characteristic. A pairing result of 1 means it is ready for the picture.
 */
@SuppressLint("MissingPermission")
class TftBle(
    private val context: Context,
    private val onLinked: () -> Unit,
    private val onReady: () -> Unit
) {
    companion object {
        private val SERVICES = listOf(
            "0000e0ff-3c17-d293-8e48-14fe2e4da212",
            "0000e0ff-3c17-d293-8e48-14fe2e4da213",
            "0000e0ff-3e17-d293-8e48-14fe2e4da212",
            "0000e0ff-4017-d293-8e48-14fe2e4da212"
        ).map(UUID::fromString)
        private val WRITE_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_CHAR = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val WRITE_GAP_MS = 150L
        private const val STATUS_EVERY_MS = 5000L
        private const val MAX_RETRIES = 4

        /** The paired Kove TFT (its Bluetooth name contains "CQKY"), or null. */
        fun findPairedTft(): String? = try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
                ?.takeIf { it.isEnabled }
                ?.bondedDevices
                ?.firstOrNull { (it.name ?: "").contains("CQKY", ignoreCase = true) }
                ?.address
        } catch (_: SecurityException) {
            null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val outbox = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var address: String? = null
    private var retries = 0
    @Volatile private var stopped = true
    @Volatile private var linked = false

    fun connect(mac: String) {
        address = mac
        stopped = false
        retries = 0
        open()
    }

    fun disconnect() {
        stopped = true
        linked = false
        main.removeCallbacksAndMessages(null)
        draining = false
        synchronized(outbox) { outbox.clear() }
        close()
        KoveLog.i("BLE disconnected")
    }

    /** Repeats the opening messages, which also asks a TFT that dropped Wi-Fi to come back. */
    fun wake() {
        if (linked) sendHello()
    }

    // ─── Connection ───────────────────────────────────────────────

    private fun open() {
        close()
        val mac = address ?: return
        val device = try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
        } catch (e: Exception) {
            KoveLog.e("BLE: bad TFT address $mac")
            null
        } ?: return
        KoveLog.i("BLE connecting to $mac")
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            KoveLog.e("BLE: no Bluetooth permission")
            null
        }
    }

    private fun close() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        writeChar = null
    }

    private fun retry(reason: String) {
        linked = false
        close()
        if (stopped) return
        if (retries >= MAX_RETRIES) {
            KoveLog.w("BLE: $reason - giving up after $retries retries")
            return
        }
        val delay = 2000L shl retries
        retries++
        KoveLog.w("BLE: $reason - retry $retries in ${delay / 1000} s")
        main.postDelayed({ if (!stopped) open() }, delay)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> retry("connection error $status")
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        if (!g.requestMtu(512)) g.discoverServices()
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> retry("link lost")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val service = SERVICES.firstNotNullOfOrNull { g.getService(it) }
                    ?: g.services.firstOrNull { it.getCharacteristic(WRITE_CHAR) != null && it.getCharacteristic(NOTIFY_CHAR) != null }
                val w = service?.getCharacteristic(WRITE_CHAR)
                val n = service?.getCharacteristic(NOTIFY_CHAR)
                if (status != BluetoothGatt.GATT_SUCCESS || w == null || n == null) {
                    retry("TFT service not found")
                    return@post
                }
                writeChar = w
                g.setCharacteristicNotification(n, true)
                val cccd = n.getDescriptor(CCCD)
                if (cccd == null) {
                    retry("notify descriptor missing")
                    return@post
                }
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retry("notify setup failed $status")
                    return@post
                }
                synchronized(outbox) { outbox.clear() }
                linked = true
                retries = 0
                KoveLog.i("BLE linked to the TFT")
                onLinked()
                sendHello()
                main.postDelayed(statusTick, STATUS_EVERY_MS)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val text = String(c.value ?: return, Charsets.UTF_8)
            main.post { onMessage(text) }
        }
    }

    // ─── Messages ─────────────────────────────────────────────────

    private fun onMessage(text: String) {
        KoveLog.i("BLE from TFT: ${text.replace(Regex("\\s+"), " ").take(160)}")
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        VehicleData.update(json)
        val paired = json.optInt("msg_id") == 27 &&
            json.optString("act") == "send_pairresult" &&
            json.optInt("result") == 1
        if (paired) {
            send(status(23))
            send(status(21))
            onReady()
        }
    }

    private fun sendHello() {
        send(JSONObject().put("msg_id", 27).put("func", "PAIR").put("act", "get_pairinfo"))
        send(JSONObject().put("msg_id", 13))
        send(JSONObject().put("msg_id", 25).put("msg_type", 18).put("msg_source", 2).put("language", 2))
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        send(JSONObject().put("msg_id", 11).put("time", now).put("tag", -1))
    }

    private fun status(type: Int) =
        JSONObject().put("msg_id", 25).put("msg_type", type).put("msg_source", 2).put("status", 1)

    private val statusTick = object : Runnable {
        override fun run() {
            if (!linked || stopped) return
            send(status(24))
            main.postDelayed(this, STATUS_EVERY_MS)
        }
    }

    // Writes go out one at a time with a short gap; the TFT drops writes that arrive back to back.
    // Everything here runs on the main thread.
    private var draining = false

    private fun send(json: JSONObject) {
        synchronized(outbox) { outbox.addLast(json.toString().toByteArray(Charsets.UTF_8)) }
        main.post { if (!draining) { draining = true; drain.run() } }
    }

    private val drain = object : Runnable {
        override fun run() {
            val g = gatt
            val w = writeChar
            val next = synchronized(outbox) { outbox.peekFirst() }
            if (next == null || g == null || w == null || !linked) {
                draining = false
                return
            }
            @Suppress("DEPRECATION")
            val accepted = try {
                w.value = next
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(w)
            } catch (_: Exception) {
                false
            }
            if (accepted) synchronized(outbox) { outbox.pollFirst() }
            main.postDelayed(this, WRITE_GAP_MS)
        }
    }
}
