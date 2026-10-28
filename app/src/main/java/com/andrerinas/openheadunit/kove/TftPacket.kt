package com.andrerinas.openheadunit.kove

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Byte formats spoken on the Kove TFT's TCP ports.
 *
 * Binary packet: [kind][command][0x00 0x00][payload length, 16-bit big endian][payload].
 *   kind 0x02 command 0x01 with no payload is the keep-alive both sides send.
 * JSON frame:   0xEE 0xFD [length, 32-bit big endian] [UTF-8 JSON] 0xFF.
 * Video header: 65 bytes of client name (ASCII, zero padded), width and height as 16-bit big endian.
 */
object TftPacket {

    val KEEP_ALIVE: ByteArray = binary(0x02, 0x01)

    fun binary(kind: Int, command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(6 + payload.size)
        out[0] = kind.toByte()
        out[1] = command.toByte()
        out[4] = ((payload.size shr 8) and 0xFF).toByte()
        out[5] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 6)
        return out
    }

    fun jsonFrame(json: String): ByteArray {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(body.size + 7)
        out.write(0xEE)
        out.write(0xFD)
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((body.size shr shift) and 0xFF)
        out.write(body)
        out.write(0xFF)
        return out.toByteArray()
    }

    fun videoHeader(width: Int, height: Int, clientName: String = "android"): ByteArray {
        val out = ByteArray(69)
        val name = clientName.toByteArray(StandardCharsets.US_ASCII)
        name.copyInto(out, 0, 0, minOf(name.size, 65))
        out[65] = (width shr 8).toByte()
        out[66] = width.toByte()
        out[67] = (height shr 8).toByte()
        out[68] = height.toByte()
        return out
    }

    /** What the phone sends once the TFT has answered on the control port. */
    fun controlGreeting(): List<ByteArray> {
        val account = ByteArray(256)
        // Account field the TFT expects to be filled; the value seen working on the 800X.
        "yahoo@yahoo.com".toByteArray(StandardCharsets.US_ASCII).copyInto(account)
        return listOf(
            binary(0x01, 0x01),
            binary(0x01, 0x17, byteArrayOf(0, 0, 0, 2)),
            binary(0x01, 0x12, account),
            binary(0x01, 0x0E),
            binary(0x01, 0x11),
            jsonFrame("""{"msg_id":27,"func":"INSIDENAVI","query":2}"""),
            jsonFrame("""{"msg_id":27,"func":"INSIDENAVI","query":1}""")
        )
    }

    /**
     * Splits the control port's incoming byte stream into messages.
     * Returns how many bytes were consumed; unknown bytes are skipped to the next known start.
     */
    sealed class Incoming {
        object KeepAlive : Incoming()
        data class Json(val text: String) : Incoming()
        data class Binary(val kind: Int, val command: Int, val payload: ByteArray) : Incoming()
    }

    class Reader {
        private val pending = ByteArrayOutputStream()

        fun feed(data: ByteArray, length: Int): List<Incoming> {
            pending.write(data, 0, length)
            val bytes = pending.toByteArray()
            val messages = ArrayList<Incoming>()
            var pos = 0
            loop@ while (pos < bytes.size) {
                val b0 = bytes[pos].toInt() and 0xFF
                when {
                    b0 == 0xEE -> {
                        if (bytes.size - pos < 6) break@loop
                        if ((bytes[pos + 1].toInt() and 0xFF) != 0xFD) { pos++; continue@loop }
                        val len = readInt(bytes, pos + 2)
                        if (len < 0 || len > 65536) { pos++; continue@loop }
                        val total = 6 + len + 1
                        if (bytes.size - pos < total) break@loop
                        messages += Incoming.Json(String(bytes, pos + 6, len, Charsets.UTF_8))
                        pos += total
                    }
                    b0 == '{'.code -> {
                        val end = matchingBrace(bytes, pos)
                        if (end < 0) break@loop
                        messages += Incoming.Json(String(bytes, pos, end - pos + 1, Charsets.UTF_8))
                        pos = end + 1
                    }
                    b0 == 0x01 || b0 == 0x02 -> {
                        if (bytes.size - pos < 6) break@loop
                        val len = ((bytes[pos + 4].toInt() and 0xFF) shl 8) or (bytes[pos + 5].toInt() and 0xFF)
                        if (bytes.size - pos < 6 + len) break@loop
                        val command = bytes[pos + 1].toInt() and 0xFF
                        messages += if (b0 == 0x02 && command == 0x01 && len == 0) Incoming.KeepAlive
                        else Incoming.Binary(b0, command, bytes.copyOfRange(pos + 6, pos + 6 + len))
                        pos += 6 + len
                    }
                    else -> pos++
                }
            }
            pending.reset()
            if (pos < bytes.size) pending.write(bytes, pos, bytes.size - pos)
            return messages
        }

        private fun readInt(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

        private fun matchingBrace(b: ByteArray, start: Int): Int {
            var depth = 0
            for (i in start until b.size) {
                when (b[i].toInt()) {
                    '{'.code -> depth++
                    '}'.code -> if (--depth == 0) return i
                }
            }
            return -1
        }
    }
}
