package com.deepaksigroha.imageclean.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Saves exported copies. Originals are never overwritten – every export gets a new file name. */
object MediaSaver {
    private const val FOLDER = "ImageClean Pro"

    data class Saved(val uri: Uri, val displayPath: String, val file: File?)

    fun saveImage(ctx: Context, bytes: ByteArray, name: String, mime: String): Saved =
        save(ctx, bytes, uniqueName(name), mime, Environment.DIRECTORY_PICTURES, isImage = true)

    fun saveDocument(ctx: Context, bytes: ByteArray, name: String, mime: String): Saved =
        save(ctx, bytes, uniqueName(name), mime, Environment.DIRECTORY_DOWNLOADS, isImage = false)

    private fun save(ctx: Context, bytes: ByteArray, name: String, mime: String, dir: String, isImage: Boolean): Saved {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isImage) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = ctx.contentResolver.insert(collection, values) ?: error("Could not create output file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write output file")
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            return Saved(uri, "$dir/$FOLDER/$name", null)
        }
        // Android 9: app-specific external storage (no storage permission required) + share sheet.
        val folder = File(ctx.getExternalFilesDir(dir), FOLDER).apply { mkdirs() }
        val f = File(folder, name); f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        return Saved(uri, f.absolutePath, f)
    }

    /** Writes to cache and returns a shareable content URI (used for reports and quick sharing). */
    fun cacheFile(ctx: Context, sub: String, name: String, bytes: ByteArray): Uri {
        val d = File(ctx.cacheDir, sub).apply { mkdirs() }
        val f = File(d, name); f.writeBytes(bytes)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    }

    fun shareIntent(uri: Uri, mime: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share",
    )

    private fun uniqueName(name: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        return if (ext.isEmpty()) "${base}_$stamp" else "${base}_$stamp.$ext"
    }

    fun outputName(original: String, suffix: String, ext: String): String =
        original.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + "_$suffix.$ext"
}
