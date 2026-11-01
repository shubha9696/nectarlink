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
import android.widget.Toast
import app.nectarlink.android.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val externalUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.registerContentObserver(externalUri, true, this)
            runCatching {
                val internalUri = MediaStore.Images.Media.INTERNAL_CONTENT_URI
                context.contentResolver.registerContentObserver(internalUri, true, this)
            }
            Log.i(TAG, "ScreenshotObserver registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ScreenshotObserver", e)
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
        checkAndSyncScreenshot(uri)
    }

    private fun checkAndSyncScreenshot(triggerUri: Uri? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                // Short initial delay so system screenshot compressor begins writing file
                delay(250L)

                val resolver = context.contentResolver
                val projection = mutableListOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATA,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projection.add(MediaStore.Images.Media.RELATIVE_PATH)
                    projection.add(MediaStore.MediaColumns.IS_PENDING)
                }

                // Query by _ID DESC to always get the most recently added image record
                val sortOrder = "${MediaStore.Images.Media._ID} DESC"
                val baseUri = if (triggerUri != null && triggerUri.toString().contains("internal")) {
                    MediaStore.Images.Media.INTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                resolver.query(
                    baseUri,
                    projection.toTypedArray(),
                    null,
                    null,
                    sortOrder,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use

                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateAddedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                    val id = cursor.getLong(idColumn)
                    val displayName = if (nameColumn != -1) cursor.getString(nameColumn) ?: "" else ""
                    val dateAddedSec = if (dateAddedColumn != -1) cursor.getLong(dateAddedColumn) else 0L
                    val filePath = if (dataColumn != -1) cursor.getString(dataColumn) ?: "" else ""

                    var relPath = ""
                    var isPending = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        if (pathColumn != -1) {
                            relPath = cursor.getString(pathColumn) ?: ""
                        }
                        val pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                        if (pendingColumn != -1) {
                            isPending = cursor.getInt(pendingColumn) == 1
                        }
                    }

                    // Check if file is a screenshot across name, folder, and path
                    val isScreenshot = isScreenshotFile(displayName, relPath, filePath)
                    if (!isScreenshot) {
                        return@use
                    }

                    // Don't process the same screenshot twice in a short window
                    val nowMs = System.currentTimeMillis()
                    if (id == lastHandledId && (nowMs - lastHandledTimeMs < 3000L)) {
                        return@use
                    }

                    // Wait if system is still writing the file
                    if (isPending) {
                        delay(350L)
                    }

                    val imageUri = ContentUris.withAppendedId(baseUri, id)

                    // Progressive retry loop to ensure SystemUI has completely finished flushing the file
                    var ready = false
                    for (attempt in 0..4) {
                        val readResult = withContext(Dispatchers.IO) {
                            PhoneClipboard.readImage(context, imageUri)
                        }
                        if (readResult is PhoneClipboard.ImageResult.Ready) {
                            ready = true
                            break
                        }
                        delay(250L + attempt * 200L)
                    }

                    if (!ready) {
                        Log.w(TAG, "Screenshot $displayName could not be read after retries")
                        return@use
                    }

                    lastHandledId = id
                    lastHandledTimeMs = nowMs

                    Log.i(TAG, "New screenshot verified: $displayName (path=$filePath). Beaming to PC clipboard...")
                    val result = core.sendClipboardImage(imageUri)
                    Log.i(TAG, "sendClipboardImage result: $result")

                    withContext(Dispatchers.Main) {
                        runCatching {
                            Toast.makeText(context, "Screenshot sent to PC clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in checkAndSyncScreenshot", e)
            }
        }
    }

    private fun isScreenshotFile(name: String, relPath: String, path: String): Boolean {
        val keywords = listOf("screenshot", "screen_", "screencap", "capture", "screen_recording")
        val lowerName = name.lowercase()
        val lowerRel = relPath.lowercase()
        val lowerPath = path.lowercase()

        for (k in keywords) {
            if (lowerName.contains(k) || lowerRel.contains(k) || lowerPath.contains(k)) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "ScreenshotObserver"
    }
}
