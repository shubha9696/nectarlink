// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.clipboard

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import app.nectarlink.android.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Monitors the device media store for new screenshots and sends them
 * immediately to paired PCs over the encrypted clipboard channel.
 */
class ScreenshotObserver(
    private val context: Context,
    private val core: Core,
    private val scope: CoroutineScope,
    handler: Handler = Handler(Looper.getMainLooper()),
) : ContentObserver(handler) {

    private var lastHandledId: Long = -1L
    private var lastHandledTimeMs: Long = 0L

    fun register() {
        try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.registerContentObserver(uri, true, this)
            initLatestId()
            Log.i(TAG, "ScreenshotObserver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ScreenshotObserver", e)
        }
    }

    private fun initLatestId() {
        scope.launch(Dispatchers.IO) {
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        lastHandledId = cursor.getLong(0)
                        lastHandledTimeMs = System.currentTimeMillis()
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    fun unregister() {
        try {
            context.contentResolver.unregisterContentObserver(this)
            Log.i(TAG, "ScreenshotObserver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister ScreenshotObserver", e)
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        checkAndSyncScreenshot()
    }

    private fun checkAndSyncScreenshot() {
        scope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val projection = mutableListOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DISPLAY_NAME,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projection.add(MediaStore.Images.Media.RELATIVE_PATH)
                }

                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection.toTypedArray(),
                    null,
                    null,
                    sortOrder,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use

                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                    val id = cursor.getLong(idColumn)
                    val dateAddedSec = cursor.getLong(dateAddedColumn)
                    val displayName = cursor.getString(nameColumn) ?: ""

                    val nameLower = displayName.lowercase()
                    var isScreenshot = nameLower.contains("screenshot") ||
                        nameLower.contains("screen_shot") ||
                        nameLower.contains("capture") ||
                        nameLower.startsWith("scr_") ||
                        nameLower.startsWith("screenshot")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        if (pathColumn != -1) {
                            val relPath = (cursor.getString(pathColumn) ?: "").lowercase()
                            if (relPath.contains("screenshot") || relPath.contains("capture") || relPath.contains("screen_shot")) {
                                isScreenshot = true
                            }
                        }
                    }

                    val nowSec = System.currentTimeMillis() / 1000L
                    val isRecent = dateAddedSec == 0L || (nowSec - dateAddedSec) in -120..600

                    if (isScreenshot && isRecent && id != lastHandledId) {
                        lastHandledId = id
                        lastHandledTimeMs = System.currentTimeMillis()

                        val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        Log.i(TAG, "New screenshot detected: $displayName (id=$id). Beaming to PC clipboard.")
                        core.sendClipboardImage(imageUri)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in screenshot inspection query", e)
            }
        }
    }

    companion object {
        private const val TAG = "ScreenshotObserver"
    }
}
