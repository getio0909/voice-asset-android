package com.voiceasset.android.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class RecordingFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = resolve(uri)?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val resolved = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(resolved.file.name)
                OpenableColumns.SIZE -> row.add(resolved.file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("recordings are read-only")
        }
        val resolved = resolve(uri) ?: throw FileNotFoundException("recording is unavailable")
        return ParcelFileDescriptor.open(resolved.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri = throw UnsupportedOperationException("recordings are read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("recordings are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("recordings are read-only")

    private fun resolve(uri: Uri): ResolvedRecording? {
        val providerContext = context ?: return null
        if (uri.scheme != "content" || uri.authority != authority(providerContext.packageName)) {
            return null
        }
        val fileName = uri.pathSegments.singleOrNull() ?: return null
        if (!isSupportedRecordingName(fileName)) {
            return null
        }
        val requestedDirectory = File(providerContext.filesDir, RECORDING_DIRECTORY).absoluteFile
        val directory = requestedDirectory.canonicalFile
        val file = File(directory, fileName).canonicalFile
        if (directory.path != requestedDirectory.path || file.parentFile != directory || !file.isFile) {
            return null
        }
        return ResolvedRecording(file = file, mimeType = mimeType(fileName))
    }

    private data class ResolvedRecording(
        val file: File,
        val mimeType: String,
    )

    companion object {
        internal const val RECORDING_DIRECTORY = "recordings"

        internal fun authority(packageName: String): String = "$packageName.recordings"

        internal fun uri(
            packageName: String,
            fileName: String,
        ): Uri =
            Uri
                .Builder()
                .scheme("content")
                .authority(authority(packageName))
                .appendPath(fileName)
                .build()

        internal fun isSupportedRecordingName(fileName: String): Boolean =
            fileName.isNotBlank() &&
                fileName == fileName.trim() &&
                fileName != "." &&
                fileName != ".." &&
                fileName.length <= 255 &&
                '/' !in fileName &&
                '\\' !in fileName &&
                fileName.none(Char::isISOControl) &&
                (fileName.endsWith(".m4a", ignoreCase = true) || fileName.endsWith(".wav", ignoreCase = true))

        internal fun mimeType(fileName: String): String =
            if (fileName.endsWith(".wav", ignoreCase = true)) {
                "audio/wav"
            } else {
                "audio/mp4"
            }
    }
}
