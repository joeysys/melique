package com.hiwtata.melique

import com.hiwtata.melique.data.Disposition
import com.hiwtata.melique.data.Song
import com.hiwtata.melique.data.AlbumDeletePhrase
import com.hiwtata.melique.data.albumDeletePhrase
import com.hiwtata.melique.data.planAlbumDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDeleteTest {
    @Test
    fun keepsMarkedSongsAndDeletesPendingWithTheRest() {
        val songs = listOf(song("plain"), song("later"), song("stay"), song("stay-2"))
        val marks = mapOf(
            "later" to Disposition.PENDING,
            "stay" to Disposition.KEEP,
            "stay-2" to Disposition.KEEP,
        )
        val plan = planAlbumDelete(songs, marks)
        assertEquals(listOf("plain", "later"), plan.removable.map { it.key })
        assertEquals(2, plan.kept)
        assertEquals(1, plan.pending)
        assertTrue(plan.needsConfirm)
        assertEquals(AlbumDeletePhrase.Mixed, albumDeletePhrase(plan))
    }

    @Test
    fun pendingOnlyStillAsksAndDeletesEverything() {
        val songs = listOf(song("a"), song("b"))
        val plan = planAlbumDelete(songs, mapOf("b" to Disposition.PENDING))
        assertEquals(2, plan.removable.size)
        assertEquals(0, plan.kept)
        assertTrue(plan.needsConfirm)
        assertEquals(AlbumDeletePhrase.AllPending, albumDeletePhrase(plan))
    }

    @Test
    fun unmarkedAlbumDeletesImmediately() {
        val songs = listOf(song("a"), song("b"))
        val plan = planAlbumDelete(songs, emptyMap())
        assertFalse(plan.needsConfirm)
        assertEquals(2, plan.removable.size)
    }

    @Test
    fun allKeptLeavesNothingToDelete() {
        val songs = listOf(song("a"))
        val plan = planAlbumDelete(songs, mapOf("a" to Disposition.KEEP))
        assertTrue(plan.removable.isEmpty())
        assertEquals(1, plan.kept)
    }

    private fun song(key: String) = Song(
        id = 1,
        key = key,
        title = key,
        artist = "artist",
        album = "Night",
        albumKey = "Night",
        albumId = 1,
        durationMs = 1,
        track = 1,
        disc = 1,
        uriString = "content://test/$key",
        path = null,
    )
}