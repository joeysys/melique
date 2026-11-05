package com.hiwtata.melique

import com.hiwtata.melique.ui.CoverAction
import com.hiwtata.melique.ui.CoverInput
import com.hiwtata.melique.ui.interpretCover
import com.hiwtata.melique.ui.shouldOpenQueue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverGestureTest {
    @Test
    fun normalModeMovesAndDoesNotMark() {
        assertEquals(CoverAction.Next, interpretCover(CoverInput.Left, selecting = false, confirming = false, kept = false))
        assertEquals(CoverAction.Previous, interpretCover(CoverInput.Right, selecting = false, confirming = false, kept = false))
        assertEquals(CoverAction.OpenQueue, interpretCover(CoverInput.Up, selecting = false, confirming = false, kept = false))
        assertEquals(CoverAction.Collapse, interpretCover(CoverInput.Down, selecting = false, confirming = false, kept = false))
        assertEquals(CoverAction.Ignore, interpretCover(CoverInput.Tap, selecting = false, confirming = false, kept = false))
        assertEquals(CoverAction.Ignore, interpretCover(CoverInput.LongPress, selecting = false, confirming = false, kept = false))
    }

    @Test
    fun selectionSwipeMarksPendingUnlessTheSongIsKept() {
        assertEquals(CoverAction.PendingAndNext, interpretCover(CoverInput.Left, selecting = true, confirming = false, kept = false))
        assertEquals(CoverAction.PendingAndPrevious, interpretCover(CoverInput.Right, selecting = true, confirming = false, kept = false))
        assertEquals(CoverAction.Next, interpretCover(CoverInput.Left, selecting = true, confirming = false, kept = true))
        assertEquals(CoverAction.Previous, interpretCover(CoverInput.Right, selecting = true, confirming = false, kept = true))
        assertEquals(CoverAction.KeepAndAdvance, interpretCover(CoverInput.LongPress, selecting = true, confirming = false, kept = false))
        assertEquals(CoverAction.OpenDelete, interpretCover(CoverInput.Up, selecting = true, confirming = false, kept = false))
        assertEquals(CoverAction.Collapse, interpretCover(CoverInput.Down, selecting = true, confirming = false, kept = false))
        assertEquals(CoverAction.Ignore, interpretCover(CoverInput.Tap, selecting = true, confirming = false, kept = false))
    }

    @Test
    fun deleteConfirmOnlyHasThreeGestures() {
        assertEquals(CoverAction.DeleteSong, interpretCover(CoverInput.Left, selecting = true, confirming = true, kept = false))
        assertEquals(CoverAction.DeleteAlbum, interpretCover(CoverInput.Right, selecting = true, confirming = true, kept = true))
        assertEquals(CoverAction.CancelDelete, interpretCover(CoverInput.Down, selecting = true, confirming = true, kept = false))
        assertEquals(CoverAction.Ignore, interpretCover(CoverInput.Up, selecting = true, confirming = true, kept = false))
        assertEquals(CoverAction.Ignore, interpretCover(CoverInput.Tap, selecting = true, confirming = true, kept = false))
        assertEquals(CoverAction.Ignore, interpretCover(CoverInput.LongPress, selecting = true, confirming = true, kept = false))
    }

    @Test
    fun upwardDragOpensQueueAndDownwardFlingCancels() {
        assertTrue(shouldOpenQueue(dy = -80f, vy = 0f, threshold = 72f))
        assertFalse(shouldOpenQueue(dy = -20f, vy = 0f, threshold = 72f))
        assertTrue(shouldOpenQueue(dy = -10f, vy = -1200f, threshold = 72f))
        assertFalse(shouldOpenQueue(dy = -200f, vy = 1200f, threshold = 72f))
    }
}