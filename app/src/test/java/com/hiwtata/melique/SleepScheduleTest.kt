package com.hiwtata.melique

import com.hiwtata.melique.player.SleepSchedule
import com.hiwtata.melique.player.formatSleepRemaining
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScheduleTest {
    @Test
    fun durationReplacesEndOfTrack() {
        val sleep = SleepSchedule()
        sleep.armTrack("song-a")
        assertEquals(91_000L, sleep.armDuration(nowElapsed = 1_000L, durationMs = 90_000L))
        assertNull(sleep.trackId)
        assertEquals(91_000L, sleep.deadlineElapsed)
    }

    @Test
    fun automaticAdvancePausesAndManualSkipCancels() {
        val sleep = SleepSchedule()
        sleep.armTrack("song-a")
        assertFalse(sleep.onTrackChanged("song-a", automatic = true))
        assertEquals("song-a", sleep.trackId)

        assertTrue(sleep.onTrackChanged("song-b", automatic = true))
        assertNull(sleep.trackId)

        sleep.armTrack("song-b")
        assertFalse(sleep.onTrackChanged("song-c", automatic = false))
        assertNull(sleep.trackId)
    }

    @Test
    fun playbackEndedFiresOnce() {
        val sleep = SleepSchedule()
        sleep.armTrack("song-a")
        assertTrue(sleep.onPlaybackEnded())
        assertFalse(sleep.onPlaybackEnded())
        assertFalse(sleep.onTrackChanged(null, automatic = true))
    }

    @Test
    fun formatsRemainingClock() {
        assertEquals("0:00", formatSleepRemaining(0))
        assertEquals("0:01", formatSleepRemaining(1))
        assertEquals("1:00", formatSleepRemaining(60_000))
        assertEquals("15:00", formatSleepRemaining(15 * 60_000L))
        assertEquals("1:01:01", formatSleepRemaining(3_661_000))
    }
}