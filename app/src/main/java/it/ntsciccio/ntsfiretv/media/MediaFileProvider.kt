// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Minimal read-only ContentProvider to pass videos to VLC.
 *
 * With targetSdk ≥ 24 a `file://` in intents crashes the app (FileUriExposedException),
 * so `content://<package>.files/<percorso assoluto>` (absolute path) is used. The provider is not exported:
 * VLC can read only the files it receives `FLAG_GRANT_READ_URI_PERMISSION` for, and only
 * if they are on a mounted volume.
 */
class MediaFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns: Array<out String> = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(values) }
    }

    override fun getType(uri: Uri): String? = fileFor(uri)?.let(::mimeTypeOf)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("NTSFireTV condivide i file solo in lettura")
        val file = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    /** File referenced by the URI, only if it exists and is inside a mounted volume. */
    private fun fileFor(uri: Uri): File? {
        val context: Context = context ?: return null
        val path: String = uri.path ?: return null
        return try {
            val file = File(path).canonicalFile
            val allowed = StorageVolumes.list(context).any { volume ->
                file.path.startsWith(volume.root.canonicalPath + File.separator)
            }
            if (allowed && file.isFile) file else null
        } catch (e: IOException) {
            null
        }
    }

    companion object {

        fun uriFor(context: Context, file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .path(file.absolutePath)
            .build()

        fun mimeTypeOf(file: File): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "video/*"

        private fun authority(context: Context): String = "${context.packageName}.files"
    }
}
