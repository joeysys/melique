package com.hiwtata.melique.data

import android.content.Context
import com.hiwtata.melique.R

enum class Disposition {
    NONE,
    PENDING,
    KEEP,
}

enum class QueueMode {
    /** Albums in random order, tracks inside an album stay in disc/track order. */
    ALBUM_SHUFFLE,

    /** Every song in random order. */
    SONG_SHUFFLE,
}

data class Song(
    val id: Long,
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumKey: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val disc: Int,
    val uriString: String,
    val path: String?,
) {
    fun displayArtist(context: Context): String = artist.ifBlank { context.getString(R.string.unknown_artist) }
    fun displayAlbum(context: Context): String = album.ifBlank { context.getString(R.string.unknown_album) }
}

data class AlbumGroup(
    val albumKey: String,
    val albumId: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
)

data class SavedPlaylist(
    val id: String,
    val name: String,
    val songKeys: List<String>,
    val createdAt: Long,
)

data class SessionState(
    val title: String = "",
    val keys: List<String> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
)

data class PlaySnap(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val title: String,
)

fun List<Song>.toAlbums(context: Context): List<AlbumGroup> {
    val various = context.getString(R.string.various_artists)
    return groupBy { it.albumKey }
        .map { (key, songs) ->
            val ordered = songs.sortedWith(songOrder)
            val artists = ordered.map { it.displayArtist(context) }.distinct()
            AlbumGroup(
                albumKey = key,
                albumId = ordered.first().albumId,
                title = ordered.first().displayAlbum(context),
                artist = if (artists.size == 1) artists.first() else various,
                songs = ordered,
            )
        }
        .sortedBy { it.title.lowercase() }
}

private val songOrder = compareBy<Song>({ it.disc }, { it.track }, { it.title.lowercase() })
