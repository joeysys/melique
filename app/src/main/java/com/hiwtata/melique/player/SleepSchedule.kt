package com.hiwtata.melique.player

/**
 * In-memory sleep timer. A minute timer pauses when the deadline passes.
 * "End of this track" pauses only on an automatic advance away from [trackId].
 * A manual skip cancels that mode and keeps playing.
 */
internal class SleepSchedule {
    var deadlineElapsed: Long? = null
        private set

    var trackId: String? = null
        private set

    fun armDuration(nowElapsed: Long, durationMs: Long): Long {
        trackId = null
        val deadline = nowElapsed + durationMs
        deadlineElapsed = deadline
        return deadline
    }

    fun armTrack(trackId: String?) {
        deadlineElapsed = null
        this.trackId = trackId
    }

    fun clear() {
        deadlineElapsed = null
        trackId = null
    }

    /** @return true when the armed track ended on its own and playback should pause */
    fun onTrackChanged(newTrackId: String?, automatic: Boolean): Boolean {
        val armed = trackId ?: return false
        if (newTrackId == armed) return false
        trackId = null
        return automatic
    }

    /** Last track reached STATE_ENDED without a following item. */
    fun onPlaybackEnded(): Boolean {
        if (trackId == null) return false
        trackId = null
        return true
    }
}

fun formatSleepRemaining(remainingMs: Long): String {
    val seconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
