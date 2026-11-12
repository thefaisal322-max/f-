package com.fainet.faisalai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Files the app makes are kept in the phone's own Download folder, in a folder per feature:
 * Download/Faisal/F+/BVR, .../Share, .../Scans, .../Codes, .../Notes.
 * They stay on the phone when the app is removed and show up in any file manager.
 */
object Public {
    const val ROOT = "Faisal/F+"

    class Entry(val name: String, val uri: Uri, val size: Long, val folder: String) {
        val path: String get() = "Download/$ROOT/$folder/$name"
    }

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Copies [src] into Download/Faisal/[folder]. Returns where it went, or null when the phone refused. */
    fun save(c: Context, folder: String, name: String, src: File, mime: String = mime(name)): Uri? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$ROOT/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null else {
                try {
                    (c.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("no stream")).use { out ->
                        src.inputStream().use { it.copyTo(out, 1024 * 1024) }
                    }
                    c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    uri
                } catch (e: Exception) {
                    c.contentResolver.delete(uri, null, null)
                    null
                }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$ROOT/$folder")
            dir.mkdirs()
            val dest = File(dir, name)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(c, arrayOf(dest.path), null, null)
            Uri.fromFile(dest)
        }
    } catch (e: Exception) {
        null
    }

    fun saveText(c: Context, folder: String, name: String, text: String): Uri? {
        val tmp = File(c.cacheDir, "tmp_" + System.nanoTime() + ".txt")
        return try {
            tmp.writeText(text, Charsets.UTF_8)
            save(c, folder, name, tmp, "text/plain")
        } finally {
            tmp.delete()
        }
    }

    fun delete(c: Context, uri: Uri) {
        try {
            if (uri.scheme == "file") File(uri.path ?: return).delete() else c.contentResolver.delete(uri, null, null)
        } catch (e: Exception) { }
    }

    /** What the app saved in one feature folder, or in all of them when [folder] is null. Newest first. */
    fun list(c: Context, folder: String? = null): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val like = "Download/$ROOT/" + (if (folder != null) "$folder/" else "") + "%"
                c.contentResolver.query(
                    base,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH),
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf(like),
                    MediaStore.MediaColumns.DATE_ADDED + " DESC"
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val rel = (cur.getString(3) ?: "").trimEnd('/')
                        out.add(Entry(cur.getString(1) ?: "file", ContentUris.withAppendedId(base, cur.getLong(0)), cur.getLong(2), rel.substringAfterLast('/')))
                    }
                }
            } else {
                val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT)
                val dirs = if (folder != null) listOf(File(root, folder)) else root.listFiles()?.filter { it.isDirectory } ?: emptyList()
                dirs.forEach { d ->
                    d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                        out.add(Entry(it.name, Uri.fromFile(it), it.length(), d.name))
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }
}
