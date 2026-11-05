package com.hiwtata.melique

import com.hiwtata.melique.data.Disposition
import com.hiwtata.melique.data.PlaySnap
import com.hiwtata.melique.data.QueueBuilder
import com.hiwtata.melique.data.QueueMode
import com.hiwtata.melique.data.Song
import com.hiwtata.melique.data.keepingLivePlayback
import com.hiwtata.melique.data.removing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueTest {
    @Test
    fun albumShuffleKeepsTrackOrderInsideEachAlbum() {
        val songs = listOf(
            song("a2", "A", track = 2),
            song("b1", "B", track = 1),
            song("a1", "A", track = 1),
            song("b2", "B", track = 2),
        )
        val once = QueueBuilder.albumShuffle(songs, Random(7))
        val twice = QueueBuilder.albumShuffle(songs, Random(7))
        assertEquals(once.map { it.key }, twice.map { it.key })
        assertEquals(listOf("a1", "a2"), once.filter { it.albumKey == "A" }.map { it.key })
        assertEquals(listOf("b1", "b2"), once.filter { it.albumKey == "B" }.map { it.key })
    }

    @Test
    fun stateFilterDropsOtherMarksBeforeShufflingSongs() {
        val songs = listOf(song("keep"), song("pending"), song("plain"))
        val marks = mapOf("keep" to Disposition.KEEP, "pending" to Disposition.PENDING)
        val queue = QueueBuilder.build(
            songs,
            marks,
            allow = setOf(Disposition.NONE, Disposition.PENDING),
            mode = QueueMode.SONG_SHUFFLE,
            random = Random(1),
        )
        assertEquals(setOf("pending", "plain"), queue.map { it.key }.toSet())
        assertTrue("keep" !in queue.map { it.key })
    }

    @Test
    fun removingCurrentSongAdvancesAndRemovingTheTailStops() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val playingB = PlaySnap(songs, index = 1, positionMs = 12_000, playWhenReady = true, title = "Queue")
        val advanced = playingB.removing(setOf("b"))
        assertEquals(listOf("a", "c"), advanced.songs.map { it.key })
        assertEquals(1, advanced.index)
        assertEquals(0L, advanced.positionMs)
        assertTrue(advanced.playWhenReady)

        val atEnd = PlaySnap(songs, index = 2, positionMs = 4, playWhenReady = true, title = "Queue")
        val stopped = atEnd.removing(setOf("c"))
        assertEquals(listOf("a", "b"), stopped.songs.map { it.key })
        assertEquals(false, stopped.playWhenReady)
    }

    @Test
    fun removingASongThatIsNotCurrentKeepsThePlace() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val snap = PlaySnap(songs, index = 2, positionMs = 8_000, playWhenReady = true, title = "Queue")
        val next = snap.removing(setOf("a"))
        assertEquals("c", next.songs[next.index].key)
        assertEquals(8_000L, next.positionMs)
        assertTrue(next.playWhenReady)
    }

    @Test
    fun keepingLivePlaybackFollowsTheSongThatIsActuallyPlaying() {
        val original = PlaySnap(
            songs = listOf(song("a"), song("d"), song("n"), song("c")),
            index = 1,
            positionMs = 9_000,
            playWhenReady = true,
            title = "Queue",
        )
        val live = PlaySnap(
            songs = listOf(song("a"), song("n"), song("c")),
            index = 1,
            positionMs = 4_500,
            playWhenReady = false,
            title = "Queue",
        )
        val restored = original.keepingLivePlayback(live)
        assertEquals(listOf("a", "d", "n", "c"), restored.songs.map { it.key })
        assertEquals(2, restored.index)
        assertEquals(4_500L, restored.positionMs)
        assertEquals(false, restored.playWhenReady)
    }

    @Test
    fun keepingLivePlaybackKeepsTheSnapshotWhenTheLiveQueueIsEmpty() {
        val original = PlaySnap(
            songs = listOf(song("a"), song("b")),
            index = 1,
            positionMs = 3_000,
            playWhenReady = true,
            title = "Queue",
        )
        val live = PlaySnap(emptyList(), index = 0, positionMs = 0L, playWhenReady = false, title = "Queue")
        val restored = original.keepingLivePlayback(live)
        assertEquals(original, restored)
    }

    @Test
    fun keepingLivePlaybackFallsBackToTheSnapshotIndexWhenTheSongIsGone() {
        val original = PlaySnap(
            songs = listOf(song("a"), song("b")),
            index = 0,
            positionMs = 1_000,
            playWhenReady = true,
            title = "Queue",
        )
        val live = PlaySnap(
            songs = listOf(song("z")),
            index = 0,
            positionMs = 2_200,
            playWhenReady = false,
            title = "Queue",
        )
        val restored = original.keepingLivePlayback(live)
        assertEquals(0, restored.index)
        assertEquals(2_200L, restored.positionMs)
        assertEquals(false, restored.playWhenReady)
    }

    private fun song(key: String, albumKey: String = key, track: Int = 1) = Song(
        id = key.hashCode().toLong(),
        key = key,
        title = key,
        artist = "artist",
        album = albumKey,
        albumKey = albumKey,
        albumId = 1,
        durationMs = 180_000,
        track = track,
        disc = 1,
        uriString = "content://test/$key",
        path = "/music/$key.mp3",
    )
}
