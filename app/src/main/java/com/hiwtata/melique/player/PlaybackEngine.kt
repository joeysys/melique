package com.hiwtata.melique.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.hiwtata.melique.MainActivity
import com.hiwtata.melique.R
import com.hiwtata.melique.data.LibraryStore
import com.hiwtata.melique.data.PlaySnap
import com.hiwtata.melique.data.SessionState
import com.hiwtata.melique.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackEngine(
    context: Context,
    private val store: LibraryStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var suspendPersist = false
    private var lastErrorId: String? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private val sleep = SleepSchedule()

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    val session: MediaSession = MediaSession.Builder(appContext, player)
        .setId("sona")
        .setSessionActivity(sessionActivity())
        .build()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _title = MutableStateFlow("")
    val queueTitle: StateFlow<String> = _title.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _sleepDeadlineElapsed = MutableStateFlow<Long?>(null)
    val sleepDeadlineElapsed: StateFlow<Long?> = _sleepDeadlineElapsed.asStateFlow()

    private val _sleepUntilTrackEnds = MutableStateFlow(false)
    val sleepUntilTrackEnds: StateFlow<Boolean> = _sleepUntilTrackEnds.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startTicker() else {
                    ticker?.cancel()
                    publishClock()
                    persist()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentId = mediaItem?.mediaId
                if (currentId != null && currentId != lastErrorId) lastErrorId = null
                val automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (sleep.onTrackChanged(currentId, automatic)) {
                    player.pause()
                    _events.tryEmit(appContext.getString(R.string.track_ended_paused))
                }
                publishSleep()
                syncIndex()
                publishClock()
                persist()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                publishClock()
                if (playbackState == Player.STATE_ENDED && sleep.onPlaybackEnded()) {
                    publishSleep()
                    _events.tryEmit(appContext.getString(R.string.track_ended_paused))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId
                val next = player.currentMediaItemIndex + 1
                if (id != null && id == lastErrorId || next >= player.mediaItemCount) {
                    player.pause()
                    return
                }
                lastErrorId = id
                player.seekTo(next, 0L)
                player.prepare()
                player.play()
            }
        })
    }

    fun currentSong(): Song? = _queue.value.getOrNull(_index.value)

    fun capture(): PlaySnap {
        return PlaySnap(
            songs = _queue.value,
            index = _index.value.coerceAtLeast(0),
            positionMs = safePosition(),
            playWhenReady = player.playWhenReady,
            title = _title.value,
        )
    }

    fun playQueue(songs: List<Song>, startIndex: Int, title: String) {
        if (songs.isEmpty()) return
        restore(
            PlaySnap(
                songs = songs,
                index = startIndex.coerceIn(0, songs.lastIndex),
                positionMs = 0L,
                playWhenReady = true,
                title = title,
            ),
            forcePlay = true,
        )
    }

    /** True from [releaseFileHandles] until [restore] publishes the real index. */
    private var replacingItems = false

    fun restore(snap: PlaySnap, forcePlay: Boolean? = null) {
        checkMain()
        val play = forcePlay ?: snap.playWhenReady
        if (play) ensureService()
        suspendPersist = true
        _queue.value = snap.songs
        _title.value = snap.title
        if (snap.songs.isEmpty()) {
            player.pause()
            player.clearMediaItems()
            _index.value = 0
            _position.value = 0L
            _duration.value = 0L
            _isPlaying.value = false
            replacingItems = false
            suspendPersist = false
            persist()
            return
        }
        val index = snap.index.coerceIn(0, snap.songs.lastIndex)
        player.setMediaItems(
            snap.songs.map { it.toMediaItem() },
            index,
            snap.positionMs.coerceAtLeast(0L),
        )
        player.prepare()
        player.shuffleModeEnabled = _shuffleEnabled.value
        player.repeatMode = _repeatMode.value
        player.playWhenReady = play
        _index.value = index
        _position.value = snap.positionMs.coerceAtLeast(0L)
        replacingItems = false
        suspendPersist = false
        publishClock()
        persist()
    }

    /** Drop open file handles before a physical move. Logical queue is kept until [restore]. */
    fun releaseFileHandles() {
        checkMain()
        suspendPersist = true
        replacingItems = true
        player.pause()
        player.clearMediaItems()
        _isPlaying.value = false
        suspendPersist = false
    }

    fun insertAfterCurrent(songs: List<Song>) {
        if (songs.isEmpty()) return
        checkMain()
        val updated = _queue.value.toMutableList()
        val at = (_index.value + 1).coerceIn(0, updated.size)
        updated.addAll(at, songs)
        _queue.value = updated
        val items = songs.map { it.toMediaItem() }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.pause()
            _index.value = 0
        } else {
            val playerAt = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItems(playerAt, items)
        }
        persist()
    }

    fun toggle() {
        if (player.isPlaying) {
            player.pause()
        } else if (player.mediaItemCount > 0) {
            ensureService()
            player.play()
        }
    }

    fun toggleShuffle(): Boolean {
        checkMain()
        val enabled = !player.shuffleModeEnabled
        player.shuffleModeEnabled = enabled
        _shuffleEnabled.value = enabled
        return enabled
    }

    fun cycleRepeat(): Int {
        checkMain()
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        _repeatMode.value = next
        return next
    }

    fun neighborIndex(forward: Boolean): Int {
        if (player.mediaItemCount == 0) return C.INDEX_UNSET
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return C.INDEX_UNSET
        val mode = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val index = if (forward) {
            player.currentTimeline.getNextWindowIndex(current, mode, player.shuffleModeEnabled)
        } else {
            player.currentTimeline.getPreviousWindowIndex(current, mode, player.shuffleModeEnabled)
        }
        return if (index == current) C.INDEX_UNSET else index
    }

    fun next(): Boolean {
        val next = neighborIndex(true)
        if (next == C.INDEX_UNSET) return false
        ensureService()
        player.seekTo(next, 0L)
        _index.value = next
        publishClock()
        player.play()
        return true
    }

    fun previous(restartIfStarted: Boolean = true) {
        val index = player.currentMediaItemIndex
        if (restartIfStarted && safePosition() > 3_000L && index >= 0) {
            player.seekTo(index, 0L)
            publishClock()
            return
        }
        val previous = neighborIndex(false)
        if (previous == C.INDEX_UNSET) {
            if (index >= 0) player.seekTo(0L)
            publishClock()
            return
        }
        ensureService()
        player.seekTo(previous, 0L)
        _index.value = previous
        publishClock()
        player.play()
    }

    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        ensureService()
        player.seekTo(index, 0L)
        player.play()
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        _position.value = clamped
    }

    fun startSleep(durationMs: Long) {
        if (durationMs <= 0L) return
        checkMain()
        sleepJob?.cancel()
        val deadline = sleep.armDuration(SystemClock.elapsedRealtime(), durationMs)
        publishSleep()
        sleepJob = scope.launch {
            delay(durationMs)
            if (sleep.deadlineElapsed != deadline) return@launch
            sleep.clear()
            publishSleep()
            player.pause()
            _events.tryEmit(appContext.getString(R.string.sleep_fired))
        }
    }

    fun sleepUntilThisTrackEnds() {
        checkMain()
        val trackId = player.currentMediaItem?.mediaId ?: return
        sleepJob?.cancel()
        sleep.armTrack(trackId)
        publishSleep()
    }

    fun clearSleep() {
        checkMain()
        sleepJob?.cancel()
        sleepJob = null
        sleep.clear()
        publishSleep()
    }

    fun pause() {
        player.pause()
    }

    fun play() {
        if (player.mediaItemCount == 0) return
        ensureService()
        player.play()
    }

    private fun ensureService() {
        val intent = Intent(appContext, PlaybackService::class.java)
        try {
            appContext.startService(intent)
        } catch (_: IllegalStateException) {
            ContextCompat.startForegroundService(appContext, intent)
        }
    }

    private fun sessionActivity(): android.app.PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return android.app.PendingIntent.getActivity(
            appContext,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && player.isPlaying) {
                publishClock()
                delay(200)
            }
        }
    }

    private fun syncIndex() {
        if (suspendPersist || replacingItems) return
        val index = player.currentMediaItemIndex
        if (index >= 0) _index.value = index
    }

    private fun publishClock() {
        _position.value = safePosition()
        val duration = player.duration
        _duration.value = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
    }

    private fun safePosition(): Long {
        val position = player.currentPosition
        return if (position < 0) 0L else position
    }

    private fun publishSleep() {
        _sleepDeadlineElapsed.value = sleep.deadlineElapsed
        _sleepUntilTrackEnds.value = sleep.trackId != null
    }

    private fun persist() {
        if (suspendPersist) return
        val songs = _queue.value
        store.saveSession(
            SessionState(
                title = _title.value,
                keys = songs.map { it.key },
                index = _index.value,
                positionMs = _position.value,
            ),
        )
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    private fun Song.toMediaItem(): MediaItem {
        val artwork = if (albumId > 0) {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        } else {
            null
        }
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uriString)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(displayArtist(appContext))
                    .setAlbumTitle(displayAlbum(appContext))
                    .setArtworkUri(artwork)
                    .build(),
            )
            .build()
    }
}
