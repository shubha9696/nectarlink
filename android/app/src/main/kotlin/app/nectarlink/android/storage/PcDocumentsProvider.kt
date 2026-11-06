// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.R
import app.nectarlink.android.core.Core
import java.io.File

/**
 * Exposes connected PC drives and folders into Android's Storage Access Framework
 * and system Files app ("Files by Google" / DocumentsUI) so the user can browse,
 * open, and copy PC files directly from their phone's native file storage.
 */
class PcDocumentsProvider : DocumentsProvider() {

    private val defaultRoots = listOf(
        "C:" to "Drive C:",
        "Downloads" to "Downloads",
        "Documents" to "Documents",
        "Desktop" to "Desktop",
        "Pictures" to "Pictures",
        "Videos" to "Videos",
        "Music" to "Music",
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val core = NectarlinkApplication.instance?.core
        val pcs = core?.allPcs.orEmpty()

        if (pcs.isNotEmpty()) {
            for (pc in pcs) {
                val row = cursor.newRow()
                row.add(Root.COLUMN_ROOT_ID, "pc_${pc.id}")
                row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                row.add(Root.COLUMN_TITLE, if (pc.name.isNotBlank()) "${pc.name} (PC)" else "PC Storage")
                row.add(Root.COLUMN_SUMMARY, if (pc.online) "Online · Connected" else "Offline")
                row.add(Root.COLUMN_DOCUMENT_ID, "root:${pc.id}")
                row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                row.add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        } else {
            val row = cursor.newRow()
            row.add(Root.COLUMN_ROOT_ID, "pc_nectarlink_default")
            row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            row.add(Root.COLUMN_TITLE, "PC Storage")
            row.add(Root.COLUMN_SUMMARY, "Connect PC in NectarLink")
            row.add(Root.COLUMN_DOCUMENT_ID, "root:default")
            row.add(Root.COLUMN_FLAGS, 0)
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val row = cursor.newRow()

        if (documentId.startsWith("root:")) {
            row.add(Document.COLUMN_DOCUMENT_ID, documentId)
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            row.add(Document.COLUMN_DISPLAY_NAME, "PC Storage")
            row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            row.add(Document.COLUMN_FLAGS, 0)
            row.add(Document.COLUMN_SIZE, 0L)
            return cursor
        }

        val path = if (documentId.contains(':')) documentId.substringAfter(':') else documentId
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
        val isDir = !name.contains('.') || name.endsWith(":") || name in listOf("Downloads", "Documents", "Desktop", "Pictures", "Videos", "Music")

        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(name))
        row.add(Document.COLUMN_DISPLAY_NAME, name)
        row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
        row.add(Document.COLUMN_FLAGS, 0)
        row.add(Document.COLUMN_SIZE, 0L)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val core = NectarlinkApplication.instance?.core

        val pcId: String
        val remotePath: String

        if (parentDocumentId.startsWith("root:")) {
            pcId = parentDocumentId.removePrefix("root:")
            remotePath = ""
        } else {
            pcId = parentDocumentId.substringBefore(':')
            remotePath = parentDocumentId.substringAfter(':')
        }

        val entries = if (pcId != "default" && core != null) {
            core.listPcFilesBlocking(pcId, remotePath)
        } else {
            emptyList()
        }

        if (entries.isNotEmpty()) {
            for (entry in entries) {
                val cleanPath = entry.path.ifEmpty {
                    if (remotePath.isEmpty()) entry.name else "$remotePath/${entry.name}"
                }
                val docId = "$pcId:$cleanPath"
                val isDir = entry.isDir
                val row = cursor.newRow()
                row.add(Document.COLUMN_DOCUMENT_ID, docId)
                row.add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(entry.name))
                row.add(Document.COLUMN_DISPLAY_NAME, entry.name)
                row.add(Document.COLUMN_LAST_MODIFIED, entry.modified)
                row.add(Document.COLUMN_FLAGS, 0)
                row.add(Document.COLUMN_SIZE, entry.size.toLong())
            }
        } else if (remotePath.isEmpty()) {
            // When root is queried and offline or empty, expose default PC roots
            for ((folderKey, label) in defaultRoots) {
                val docId = "$pcId:$folderKey"
                val row = cursor.newRow()
                row.add(Document.COLUMN_DOCUMENT_ID, docId)
                row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                row.add(Document.COLUMN_DISPLAY_NAME, label)
                row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                row.add(Document.COLUMN_FLAGS, 0)
                row.add(Document.COLUMN_SIZE, 0L)
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val core = NectarlinkApplication.instance?.core ?: throw IllegalArgumentException("Core not running")
        val pcId = documentId.substringBefore(':')
        val remotePath = documentId.substringAfter(':')

        val ctx = context ?: throw IllegalStateException("No context")
        val cacheDir = File(ctx.cacheDir, "pc_saf_cache").apply { mkdirs() }
        val safeName = remotePath.substringAfterLast('/').replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "file" }
        val targetFile = File(cacheDir, "${pcId}_${remotePath.hashCode()}_$safeName")

        if (!targetFile.exists() || targetFile.length() == 0L) {
            val ok = core.downloadPcFileBlocking(pcId, remotePath, targetFile)
            if (!ok || !targetFile.exists()) {
                throw java.io.FileNotFoundException("Could not download $remotePath from PC")
            }
        }

        return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }
}
