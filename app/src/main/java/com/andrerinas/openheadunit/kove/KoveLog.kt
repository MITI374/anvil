package com.andrerinas.openheadunit.kove

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log of the Kove bridge: Logcat plus a plain text file the user can share
 * (copied to Downloads/Kove by [KoveLogPublisher]).
 */
object KoveLog {

    private const val TAG = "KoveBridge"
    const val FILE_NAME = "kove_bridge_log.txt"
    private const val MAX_BYTES = 2L * 1024 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var file: File? = null
    private var out: BufferedWriter? = null

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    /** Opens the log file once per process; later calls keep appending to it. */
    fun open(context: Context) = synchronized(lock) {
        if (out != null) return@synchronized
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.delete()
            file = f
            out = BufferedWriter(FileWriter(f, true))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open log file: ${e.message}")
        }
    }

    fun i(msg: String) = write('I', msg)
    fun w(msg: String) = write('W', msg)
    fun e(msg: String) = write('E', msg)

    private fun write(level: Char, msg: String) {
        when (level) {
            'E' -> Log.e(TAG, msg)
            'W' -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        synchronized(lock) {
            val w = out ?: return
            try {
                w.write("${stamp.format(Date())} $level $msg\n")
                w.flush()
                val f = file
                if (f != null && f.length() > MAX_BYTES) {
                    w.close()
                    f.writeText("${stamp.format(Date())} I log restarted (size limit)\n")
                    out = BufferedWriter(FileWriter(f, true))
                }
            } catch (_: Exception) {
            }
        }
    }
}
