package com.hiwtata.melique.data

import android.content.ContentUris
import android.content.Context
import com.hiwtata.melique.R
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

class MusicRepository(private val context: Context) {
    fun scan(trustFileExists: Boolean): List<Song> {
        val found = mutableListOf<Song>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volumes = MediaStore.getExternalVolumeNames(context)
            for (volume in volumes) {
                query(MediaStore.Audio.Media.getContentUri(volume), volume, trustFileExists, found)
            }
        } else {
            query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "external", trustFileExists, found)
        }
        return found.distinctBy { it.key }
    }

    private fun query(uri: Uri, volume: String, trustFileExists: Boolean, out: MutableList<Song>) {
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Audio.Media.RELATIVE_PATH
        }
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        context.contentResolver.query(
            uri,
            projection.toTypedArray(),
            selection,
            null,
            MediaStore.Audio.Media.TITLE,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relativeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol).orEmpty()
                val path = resolvePath(
                    data = if (dataCol >= 0) cursor.getString(dataCol) else null,
                    relative = if (relativeCol >= 0) cursor.getString(relativeCol) else null,
                    displayName = displayName,
                )
                if (trustFileExists && path != null && !File(path).exists()) continue
                val title = cleanName(cursor.getString(titleCol)).ifBlank {
                    displayName.substringBeforeLast('.').ifBlank { context.getString(R.string.untitled) }
                }
                val artist = cleanName(cursor.getString(artistCol))
                val album = cleanName(cursor.getString(albumCol))
                val albumId = cursor.getLong(albumIdCol)
                val trackRaw = cursor.getInt(trackCol)
                val contentUri = ContentUris.withAppendedId(uri, id)
                out += Song(
                    id = id,
                    key = path ?: "ms:$volume:$id",
                    title = title,
                    artist = artist,
                    album = album,
                    albumKey = if (albumId > 0) "alb:$albumId" else "name:${album.lowercase()}|${artist.lowercase()}",
                    albumId = albumId,
                    durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                    track = trackRaw % 1000,
                    disc = trackRaw / 1000,
                    uriString = contentUri.toString(),
                    path = path,
                )
            }
        }
    }

    private fun resolvePath(data: String?, relative: String?, displayName: String): String? {
        if (!data.isNullOrBlank()) return data
        if (!relative.isNullOrBlank() && displayName.isNotBlank()) {
            return "/storage/emulated/0/${relative.trimStart('/')}$displayName"
        }
        return null
    }

    private fun cleanName(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        if (value.equals("<unknown>", ignoreCase = true)) return ""
        if (value == MediaStore.UNKNOWN_STRING) return ""
        return value
    }
}
