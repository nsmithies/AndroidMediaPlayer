package com.studorium.androidmediaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

object ArtworkCache {
    const val MAX_BYTES: Long = 100L * 1024L * 1024L
    private const val MAX_DIMENSION = 500
    private const val JPEG_QUALITY = 82
    private const val DIRECTORY = "artwork_cache"

    private fun directory(context: Context): File =
        File(context.applicationContext.cacheDir, DIRECTORY).apply { mkdirs() }

    private fun key(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun file(context: Context, url: String) = File(directory(context), "${key(url)}.jpg")

    fun load(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val cached = file(context, url)
        if (!cached.isFile) return null
        return try {
            BitmapFactory.decodeFile(cached.absolutePath)?.also {
                cached.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            cached.delete()
            null
        }
    }

    @Synchronized
    fun store(context: Context, url: String, bitmap: Bitmap) {
        if (url.isBlank()) return
        val target = file(context, url)
        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
            return
        }

        val scaled = scale(bitmap)
        try {
            target.outputStream().buffered().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            target.setLastModified(System.currentTimeMillis())
            trim(context)
        } catch (_: Exception) {
            target.delete()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun sizeBytes(context: Context): Long =
        directory(context).listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    private fun trim(context: Context) {
        val files = directory(context).listFiles()?.filter { it.isFile }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortBy { it.lastModified() }
        for (cached in files) {
            if (total <= MAX_BYTES) break
            val bytes = cached.length()
            if (cached.delete()) total -= bytes
        }
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
