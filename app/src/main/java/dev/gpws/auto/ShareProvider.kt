package dev.gpws.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands an exported sound pack or the drive log to the app you share it with (WhatsApp, Drive…),
 * read-only and only for that share. Not exported: other apps get in solely through the one-off
 * grant on the share, and only to these two files.
 */
class ShareProvider : ContentProvider() {

    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment
        if (name != NAME && name != LOG) throw FileNotFoundException("nothing to share at $uri")
        return File(context!!.cacheDir, "share/$name")
    }

    override fun getType(uri: Uri) = if (uri.lastPathSegment == LOG) "text/plain" else "application/zip"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("shared files are read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // The name and size, which chat apps show on the attachment.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                    OpenableColumns.SIZE -> file(uri).length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val NAME = "gpws-sounds.zip"
        const val LOG = "gpws-drive-log.txt"
        val URI: Uri = Uri.parse("content://dev.gpws.auto.share/$NAME")
        val LOG_URI: Uri = Uri.parse("content://dev.gpws.auto.share/$LOG")
    }
}
