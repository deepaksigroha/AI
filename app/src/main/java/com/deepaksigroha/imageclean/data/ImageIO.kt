package com.deepaksigroha.imageclean.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

data class LoadedImage(
    val uri: Uri,
    val name: String,
    val bytes: ByteArray,
    val locationMayBeRedacted: Boolean,
)

class ImageLoadException(msg: String) : Exception(msg)

object ImageIO {
    /** Maximum file size we load fully into memory for byte-exact processing. */
    const val MAX_BYTES = 150L * 1024 * 1024

    fun displayName(cr: ContentResolver, uri: Uri): String {
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    fun load(ctx: Context, uri: Uri): LoadedImage {
        val cr = ctx.contentResolver
        var source = uri
        var redacted = false
        // On Android 10+ GPS tags are redacted unless we hold ACCESS_MEDIA_LOCATION and ask for the original.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) runCatching { MediaStore.setRequireOriginal(uri) }.getOrNull()?.let { source = it }
            redacted = !granted || uri.pathSegments.contains("picker")
        }
        val size = runCatching { cr.openAssetFileDescriptor(source, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size > MAX_BYTES) throw ImageLoadException("File is larger than ${MAX_BYTES / 1024 / 1024} MB")
        val bytes = runCatching { cr.openInputStream(source)?.use { it.readBytes() } }.getOrNull()
            ?: cr.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImageLoadException("Could not open the image")
        if (bytes.size > MAX_BYTES) throw ImageLoadException("File too large")
        return LoadedImage(uri, displayName(cr, uri), bytes, redacted)
    }

    /** Decodes a bitmap honouring EXIF orientation, downsampled so the long edge is ≤ [maxEdge]. */
    fun decode(bytes: ByteArray, maxEdge: Int, mutable: Boolean = false): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val long = maxOf(w, h)
            if (maxEdge in 1 until long) {
                val scale = maxEdge.toDouble() / long
                decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (mutable) decoder.isMutableRequired = true
        }
    }

    /** Pixel dimensions after orientation is applied. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int> = runCatching {
        var w = 0; var h = 0
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeDrawable(src) { d, info, _ -> w = info.size.width; h = info.size.height; d.setTargetSize(1, 1) }
        w to h
    }.getOrDefault(0 to 0)
}
