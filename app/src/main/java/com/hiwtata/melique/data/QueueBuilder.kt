package com.hiwtata.melique.data

import kotlin.random.Random

object QueueBuilder {
    fun build(
        songs: List<Song>,
        marks: Map<String, Disposition>,
        allow: Set<Disposition>?,
        mode: QueueMode,
        random: Random = Random,
    ): List<Song> {
        val filtered = if (allow == null) {
            songs
        } else {
            songs.filter { (marks[it.key] ?: Disposition.NONE) in allow }
        }
        return when (mode) {
            QueueMode.SONG_SHUFFLE -> filtered.shuffled(random)
            QueueMode.ALBUM_SHUFFLE -> albumShuffle(filtered, random)
        }
    }

    fun albumShuffle(songs: List<Song>, random: Random = Random): List<Song> {
        if (songs.size <= 1) return songs
        val groups = songs.groupBy { it.albumKey }.mapValues { (_, list) ->
            list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
        }
        return groups.keys.shuffled(random).flatMap { groups.getValue(it) }
    }
}

/**
 * Queue after removing [keys]. If the current song is removed, playback advances
 * to the next surviving song. When nothing remains after it, playback stops.
 */
fun PlaySnap.removing(keys: Set<String>): PlaySnap {
    if (keys.isEmpty()) return this
    val filtered = songs.filter { it.key !in keys }
    if (filtered.isEmpty()) {
        return copy(songs = emptyList(), index = 0, positionMs = 0L, playWhenReady = false)
    }
    val current = songs.getOrNull(index)
    val removingCurrent = current?.key in keys
    if (!removingCurrent) {
        val newIndex = filtered.indexOfFirst { it.key == current?.key }.coerceAtLeast(0)
        return copy(songs = filtered, index = newIndex)
    }
    val next = songs.drop(index + 1).firstOrNull { it.key !in keys }
        ?: return copy(songs = filtered, index = 0, positionMs = 0L, playWhenReady = false)
    val newIndex = filtered.indexOfFirst { it.key == next.key }.coerceAtLeast(0)
    return copy(songs = filtered, index = newIndex, positionMs = 0L, playWhenReady = true)
}
