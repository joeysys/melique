package com.hiwtata.melique.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import com.hiwtata.melique.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

val LocalArtCache = staticCompositionLocalOf<ArtCache> {
    error("ArtCache is not provided")
}

data class ArtVisual(
    val cover: ImageBitmap,
    val blur: ImageBitmap,
    val accent: Color,
)

class ArtCache(context: Context) {
    private val appContext = context.applicationContext
    private val cache = LruCache<String, ArtVisual>(8)

    suspend fun load(song: Song): ArtVisual = withContext(Dispatchers.IO) {
        cache.get(song.albumKey)?.let { return@withContext it }
        val raw = decodeAlbum(song) ?: embedded(song) ?: directoryArt(song) ?: monogram(song.displayAlbum(appContext))
        val cover = scaleDown(raw, 720)
        val blurred = blur(cover)
        val palette = Palette.from(cover).maximumColorCount(16).generate()
        val accent = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: 0xFF8A7564.toInt()
        val visual = ArtVisual(cover.asImageBitmap(), blurred.asImageBitmap(), Color(accent))
        cache.put(song.albumKey, visual)
        visual
    }

    private fun decodeAlbum(song: Song): Bitmap? {
        if (song.albumId <= 0) return null
        val uri = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            song.albumId,
        )
        return openBitmap(uri)
    }

    private fun openBitmap(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                appContext.contentResolver.loadThumbnail(uri, Size(720, 720), null)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { decodeBytes(it.readBytes()) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Song-folder images, only after album art and embedded art miss.
     * Order: cover.jpg, folder.jpg, front.jpg, then the same names as png.
     * Matching ignores case.
     */
    private fun directoryArt(song: Song): Bitmap? {
        val path = song.path ?: return null
        val dir = File(path).parentFile ?: return null
        val listed = dir.list()?.toList()
        if (listed != null) {
            val chosen = FolderCovers.match(listed)
            if (chosen == null) return null
            decodeSampled(File(dir, chosen))?.let { return it }
        } else {
            for (name in FolderCovers.names) {
                decodeSampled(File(dir, name))?.let { return it }
            }
        }
        return mediaStoreDirectoryArt(dir)
    }

    private fun mediaStoreDirectoryArt(dir: File): Bitmap? {
        val names = FolderCovers.names
        val modern = Build.VERSION.SDK_INT >= 29
        val projection = if (modern) {
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
            )
        }
        val collection = if (modern) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val placeholders = names.joinToString(",") { "?" }
        val nameClause = "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) IN ($placeholders)"
        val (selection, args) = if (modern) {
            val relative = primaryRelativePath(dir) ?: return null
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND $nameClause" to arrayOf(relative, *names.toTypedArray())
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? AND $nameClause" to
                arrayOf(dir.absolutePath.trimEnd('/') + "/%", *names.toTypedArray())
        }
        val parentPath = dir.absolutePath.trimEnd('/')
        return try {
            appContext.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = if (modern) -1 else cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                var bestRank = Int.MAX_VALUE
                var bestUri: Uri? = null
                while (cursor.moveToNext()) {
                    if (!modern) {
                        val parent = File(cursor.getString(dataCol).orEmpty()).parentFile?.absolutePath?.trimEnd('/')
                        if (parent != parentPath) continue
                    }
                    val rank = FolderCovers.rank(cursor.getString(nameCol).orEmpty())
                    if (rank < 0 || rank >= bestRank) continue
                    bestRank = rank
                    bestUri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                }
                bestUri?.let { openBitmap(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun primaryRelativePath(dir: File): String? {
        val path = dir.absolutePath.replace('\\', '/')
        val marker = "/storage/emulated/0/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        val relative = path.substring(index + marker.length).trim('/')
        if (relative.isEmpty()) return null
        return "$relative/"
    }

    private fun decodeSampled(file: File): Bitmap? {
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 720)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 720)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        val largest = max(width, height)
        while (sample < largest && largest / sample > maxSize) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun embedded(song: Song): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(song.uriString))
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun monogram(title: String): Bitmap {
        val size = 720
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colorFor(title))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.38f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val letter = title.trim().firstOrNull()?.toString() ?: "♪"
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        return bitmap
    }

    private fun colorFor(title: String): Int {
        val palette = intArrayOf(
            0xFF3E4A45.toInt(),
            0xFF4A3E3A.toInt(),
            0xFF3A4150.toInt(),
            0xFF4A4338.toInt(),
            0xFF3F3A4A.toInt(),
            0xFF3A4644.toInt(),
        )
        val index = (title.hashCode() and Int.MAX_VALUE) % palette.size
        return palette[index]
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxSize) return bitmap
        val scale = maxSize.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun blur(source: Bitmap): Bitmap {
        val width = 72
        val height = (source.height * (width.toFloat() / source.width)).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val once = boxBlur(pixels, width, height, radius = 16)
        val twice = boxBlur(once, width, height, radius = 16)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(twice, 0, width, 0, 0, width, height)
        return out
    }

    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val dest = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val pixel = source[yy * width + xx]
                        red += (pixel ushr 16) and 255
                        green += (pixel ushr 8) and 255
                        blue += pixel and 255
                        count++
                    }
                }
                dest[y * width + x] = (0xFF shl 24) or
                    ((red / count) shl 16) or
                    ((green / count) shl 8) or
                    (blue / count)
            }
        }
        return dest
    }
}
