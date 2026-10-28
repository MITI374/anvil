package com.andrerinas.openheadunit.kove

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Copies the Kove bridge log to the public Downloads/Kove folder, where a file manager can find it.
 * The app's own files folder (Android/data/...) is hidden from users on Android 11+.
 */
object KoveLogPublisher {

    private const val NAME = "kove_bridge_log.txt"
    private const val RELATIVE_DIR = "Download/Kove"

    @Volatile private var uri: Uri? = null

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val src = KoveLog.file(context)
            if (!src.exists()) return
            val resolver = context.contentResolver
            val target = uri ?: findExisting(context) ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                }
            ) ?: return
            resolver.openOutputStream(target, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } }
            uri = target
        } catch (e: Exception) {
            // A stale entry (deleted by the user) is recreated on the next publish.
            uri = null
            android.util.Log.w("KoveMirror", "Log publish failed: ${e.message}")
        }
    }

    private fun findExisting(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(NAME, "$RELATIVE_DIR/")
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
        }
        return null
    }
}
