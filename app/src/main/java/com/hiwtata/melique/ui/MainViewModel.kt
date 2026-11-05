package com.hiwtata.melique.ui

import android.app.Application
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hiwtata.melique.MusicApp
import com.hiwtata.melique.R
import com.hiwtata.melique.data.Access
import com.hiwtata.melique.data.AlbumDeletePhrase
import com.hiwtata.melique.data.AlbumGroup
import com.hiwtata.melique.data.Disposition
import com.hiwtata.melique.data.albumDeletePhrase
import com.hiwtata.melique.data.planAlbumDelete
import com.hiwtata.melique.data.PlaySnap
import com.hiwtata.melique.data.QueueBuilder
import com.hiwtata.melique.data.QueueMode
import com.hiwtata.melique.data.Song
import com.hiwtata.melique.data.keepingLivePlayback
import com.hiwtata.melique.data.removing
import com.hiwtata.melique.data.toAlbums
import androidx.media3.common.Player
import com.hiwtata.melique.player.PlaybackEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val musicApp = app as MusicApp
    private val engine: PlaybackEngine = musicApp.engine

    private val _library = MutableStateFlow<List<Song>>(emptyList())
    val library: StateFlow<List<Song>> = _library.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _audioGranted = MutableStateFlow(false)
    val audioGranted: StateFlow<Boolean> = _audioGranted.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndoDelete: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _playerOpen = MutableStateFlow(false)
    val playerOpen: StateFlow<Boolean> = _playerOpen.asStateFlow()

    val marks = musicApp.store.marks
    val trashBatches = musicApp.trash.batches
    val queue = engine.queue
    val queueIndex = engine.index
    val queueTitle = engine.queueTitle
    val isPlaying = engine.isPlaying
    val position = engine.position
    val duration = engine.duration
    val sleepDeadlineElapsed = engine.sleepDeadlineElapsed
    val shuffleEnabled = engine.shuffleEnabled
    val repeatMode = engine.repeatMode
    val sleepUntilTrackEnds = engine.sleepUntilTrackEnds

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    private val _gestureLocked = MutableStateFlow(false)
    val gestureLocked: StateFlow<Boolean> = _gestureLocked.asStateFlow()

    private val _deleteGesture = MutableStateFlow<DeleteGesture?>(null)
    val deleteGesture: StateFlow<DeleteGesture?> = _deleteGesture.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _systemDeletes = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val systemDeletes: SharedFlow<IntentSender> = _systemDeletes.asSharedFlow()

    private var pendingSystem: PendingSystemDelete? = null
    private var sessionRestored = false
    private var libraryObserver: ContentObserver? = null
    private var libraryRefreshJob: Job? = null
    private var scanGeneration = 0
    private var lastScanStartedElapsed = 0L
    private val deleteRollbacks = ArrayDeque<DeleteRollback>()

    init {
        refreshAccess()
        viewModelScope.launch {
            engine.events.collect { tell(it) }
        }
        viewModelScope.launch {
            musicApp.trash.failures.collect { failure ->
                bumpScan()
                _library.update { current -> (current + failure.songs).distinctBy { it.key } }
                restoreAfterFailedDelete(failure)
                tell(failure.message)
                scheduleLibraryRefresh()
            }
        }
    }

    override fun onCleared() {
        libraryRefreshJob?.cancel()
        libraryObserver?.let { observer ->
            getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        }
        super.onCleared()
    }

    fun albums(): List<AlbumGroup> = _library.value.toAlbums(getApplication())

    fun openPlayer() {
        _playerOpen.value = true
    }

    fun closePlayer() {
        _playerOpen.value = false
        _selecting.value = false
        _deleteGesture.value = null
    }

    fun toggleSelecting() {
        if (_gestureLocked.value || _deleteGesture.value != null) return
        _selecting.value = !_selecting.value
    }

    fun toggleGestureLock() {
        _gestureLocked.value = !_gestureLocked.value
        tell(text(if (_gestureLocked.value) R.string.gestures_locked else R.string.gestures_unlocked))
    }

    fun cover(action: CoverAction) {
        if (_gestureLocked.value) return
        when (action) {
            CoverAction.Ignore -> Unit
            CoverAction.ToggleMode -> toggleSelecting()
            CoverAction.KeepAndAdvance -> decide(Decision.KeepSong)
            CoverAction.Next -> next()
            CoverAction.Previous -> previous(fromCover = true)
            CoverAction.PendingAndNext -> pendingThen { next() }
            CoverAction.PendingAndPrevious -> pendingThen { previous(fromCover = true) }
            CoverAction.OpenDelete -> engine.currentSong()?.let { presentDeleteGesture(it) }
            CoverAction.DeleteSong -> commitGestureSongDelete()
            CoverAction.DeleteAlbum -> commitGestureAlbumDelete()
            CoverAction.CancelDelete -> cancelDeleteGesture()
            CoverAction.OpenQueue -> Unit
            CoverAction.Collapse -> closePlayer()
        }
    }

    fun refreshAccess() {
        val context = getApplication<Application>()
        val undo = Access.canUndoDelete(context)
        val audio = Access.hasAudio(context)
        _canUndo.value = undo
        _audioGranted.value = audio
        if (!audio) return
        ensureLibraryObserver()
        if (_library.value.isEmpty()) refresh() else scheduleLibraryRefresh()
    }

    fun refresh() {
        viewModelScope.launch { scan(showSpinner = _library.value.isEmpty()) }
    }

    fun startSleep(minutes: Int) {
        if (minutes <= 0) return
        engine.startSleep(minutes * 60_000L)
        tell(text(R.string.sleep_set, minutes))
    }

    fun sleepUntilThisTrackEnds() {
        if (engine.currentSong() == null) {
            tell(text(R.string.no_current_song))
            return
        }
        engine.sleepUntilThisTrackEnds()
        tell(text(R.string.sleep_end_of_track))
    }

    fun clearSleep() {
        engine.clearSleep()
        tell(text(R.string.sleep_off))
    }

    fun toggle() = engine.toggle()

    fun toggleShuffle() {
        tell(text(if (engine.toggleShuffle()) R.string.shuffle_on_toast else R.string.shuffle_off_toast))
    }

    fun cycleRepeat() {
        tell(
            when (engine.cycleRepeat()) {
                Player.REPEAT_MODE_ALL -> text(R.string.repeat_all)
                Player.REPEAT_MODE_ONE -> text(R.string.repeat_one)
                else -> text(R.string.repeat_off_toast)
            },
        )
    }

    fun neighborSong(forward: Boolean): Song? {
        val index = engine.neighborIndex(forward)
        return engine.queue.value.getOrNull(index)
    }

    /** Song that will be playing after the open album-delete gesture, or null if playback stops. */
    fun songAfterAlbumDelete(): Song? {
        val keys = _deleteGesture.value?.albumSongs?.map { it.key }?.toSet() ?: return null
        if (keys.isEmpty()) return null
        val next = engine.capture().removing(keys)
        if (!next.playWhenReady) return null
        return next.songs.getOrNull(next.index)
    }

    fun next() {
        if (!engine.next()) tell(text(R.string.last_song))
    }

    fun previous(fromCover: Boolean = false) = engine.previous(restartIfStarted = !fromCover)

    fun jumpTo(index: Int) = engine.jumpTo(index)

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun playSong(song: Song, pool: List<Song>) {
        if (pool.isEmpty()) return
        val index = pool.indexOfFirst { it.key == song.key }.coerceAtLeast(0)
        engine.playQueue(pool, index, text(R.string.songs))
        _playerOpen.value = true
    }

    fun playAlbum(album: AlbumGroup) {
        if (album.songs.isEmpty()) return
        engine.playQueue(album.songs, 0, album.title)
        _playerOpen.value = true
    }

    fun playQuick(allow: Set<Disposition>?, mode: QueueMode) {
        val songs = QueueBuilder.build(_library.value, marks.value, allow, mode)
        if (songs.isEmpty()) {
            tell(text(R.string.no_matches))
            return
        }
        engine.playQueue(songs, 0, quickTitle(allow, mode))
        _playerOpen.value = true
    }

    fun markSong(song: Song, disposition: Disposition) {
        musicApp.store.setMarks(listOf(song.key), disposition)
    }

    fun keepAlbum() {
        val song = engine.currentSong() ?: return
        val album = albumSongs(song)
        val keys = album.map { it.key }
        if (keys.all { marks.value[it] == Disposition.KEEP }) {
            tell(text(R.string.album_already_kept, song.displayAlbum(getApplication())))
            return
        }
        musicApp.store.setMarks(keys, Disposition.KEEP)
        tell(text(R.string.album_kept_count, song.displayAlbum(getApplication()), album.size))
    }

    fun clearKeep() {
        val song = engine.currentSong() ?: return
        if (marks.value[song.key] != Disposition.KEEP) {
            tell(text(R.string.song_not_kept))
            return
        }
        musicApp.store.setMarks(listOf(song.key), Disposition.NONE)
        tell(text(R.string.keep_cleared, song.title))
    }

    fun toggleKeep() {
        val song = engine.currentSong() ?: return
        val next = if (marks.value[song.key] == Disposition.KEEP) Disposition.NONE else Disposition.KEEP
        musicApp.store.setMarks(listOf(song.key), next)
    }

    fun decide(decision: Decision) {
        val song = engine.currentSong() ?: return
        when (decision) {
            Decision.DeleteSong -> delete(listOf(song), text(R.string.deleted_named, song.title))
            Decision.DeleteAlbum -> requestAlbumDelete(song)
            Decision.PendingSong -> markAndAdvance(listOf(song), Disposition.PENDING, text(R.string.pending_named, song.title))
            Decision.KeepSong -> markAndAdvance(listOf(song), Disposition.KEEP, text(R.string.kept_named, song.title))
        }
    }

    fun cancelDeleteGesture() {
        if (_deleteGesture.value == null) return
        _deleteGesture.value = null
        tell(text(R.string.delete_cancelled))
    }

    fun undo() {
        viewModelScope.launch {
            val restored = musicApp.trash.undoLatest() ?: return@launch
            restored.onSuccess { songs ->
                bumpScan()
                engine.insertAfterCurrent(songs)
                _library.update { current -> (current + songs).distinctBy { it.key } }
                tell(text(R.string.undone))
                scheduleLibraryRefresh()
            }.onFailure { error ->
                tell(error.message ?: text(R.string.undo_failed))
            }
        }
    }

    fun onSystemDeleteResult(confirmed: Boolean) {
        val pending = pendingSystem
        pendingSystem = null
        if (pending == null) return
        if (!confirmed) {
            tell(text(R.string.delete_cancelled))
            return
        }
        val next = engine.capture().removing(pending.keys)
        engine.releaseFileHandles()
        engine.restore(next)
        _library.update { current -> current.filter { it.key !in pending.keys } }
        musicApp.store.clearMarks(pending.keys)
        tell(if (next.playWhenReady) text(R.string.deleted_no_undo, pending.label) else text(R.string.deleted_nothing_left))
        viewModelScope.launch {
            delay(500)
            scan(showSpinner = false)
        }
    }

    private fun markAndAdvance(songs: List<Song>, disposition: Disposition, label: String) {
        musicApp.store.setMarks(songs.map { it.key }, disposition)
        val advanced = engine.next()
        tell(if (advanced) label else text(R.string.and_last, label))
    }

    private fun delete(songs: List<Song>, label: String) {
        val distinct = songs.distinctBy { it.key }
        if (distinct.isEmpty()) return
        val context = getApplication<Application>()
        if (Access.canUndoDelete(context)) {
            silentDelete(distinct, label)
        } else if (Build.VERSION.SDK_INT >= 30) {
            requestSystemDelete(distinct, label)
        } else {
            tell(text(R.string.no_write_permission))
        }
    }

    private fun silentDelete(songs: List<Song>, label: String) {
        val token = musicApp.trash.stageDelete(songs, label).getOrElse { error ->
            tell(error.message ?: text(R.string.delete_failed))
            return
        }
        bumpScan()
        val keys = songs.map { it.key }.toSet()
        val snap = engine.capture()
        val next = snap.removing(keys)
        rememberDeleteRollback(token, snap, next)
        engine.releaseFileHandles()
        engine.restore(next)
        _library.update { current -> current.filter { it.key !in keys } }
        musicApp.trash.startDelete(token)
    }

    private fun rememberDeleteRollback(token: Long, snap: PlaySnap, next: PlaySnap) {
        deleteRollbacks.addLast(DeleteRollback(token, snap, next))
        while (deleteRollbacks.size > 8) deleteRollbacks.removeFirst()
    }

    private fun restoreAfterFailedDelete(failure: com.hiwtata.melique.data.TrashFailure) {
        val rollback = deleteRollbacks.find { it.token == failure.token }
        deleteRollbacks.removeAll { it.token == failure.token }
        if (
            rollback != null &&
            engine.index.value == rollback.next.index &&
            engine.queue.value.map { it.key } == rollback.next.songs.map { it.key }
        ) {
            val live = engine.capture()
            engine.restore(rollback.snap.keepingLivePlayback(live))
        } else {
            engine.insertAfterCurrent(failure.songs)
        }
    }

    private fun requestSystemDelete(songs: List<Song>, label: String) {
        val uris = songs.map { Uri.parse(it.uriString) }
        val sender = try {
            MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris).intentSender
        } catch (error: Exception) {
            tell(error.message ?: text(R.string.cannot_start_system_delete))
            return
        }
        pendingSystem = PendingSystemDelete(songs.map { it.key }.toSet(), label)
        if (!_systemDeletes.tryEmit(sender)) {
            pendingSystem = null
            tell(text(R.string.cannot_start_system_delete))
        }
    }

    private fun ensureLibraryObserver() {
        if (libraryObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleLibraryRefresh()
            }
        }
        val resolver = getApplication<Application>().contentResolver
        val uris = buildSet {
            add(Uri.parse("content://media"))
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    MediaStore.getExternalVolumeNames(getApplication()).forEach { volume ->
                        add(MediaStore.Audio.Media.getContentUri(volume))
                    }
                }
            }
        }
        var registered = false
        uris.forEach { uri ->
            if (runCatching { resolver.registerContentObserver(uri, true, observer) }.isSuccess) {
                registered = true
            }
        }
        if (registered) libraryObserver = observer
    }

    private fun scheduleLibraryRefresh() {
        if (!_audioGranted.value) return
        libraryRefreshJob?.cancel()
        libraryRefreshJob = viewModelScope.launch {
            val since = SystemClock.elapsedRealtime() - lastScanStartedElapsed
            delay(maxOf(REFRESH_QUIET_MS, REFRESH_MIN_GAP_MS - since))
            scan(showSpinner = false)
        }
    }

    private fun bumpScan() {
        scanGeneration += 1
    }

    private suspend fun scan(showSpinner: Boolean) {
        if (!_audioGranted.value) return
        val generation = ++scanGeneration
        lastScanStartedElapsed = SystemClock.elapsedRealtime()
        if (showSpinner) _scanning.value = true
        val trustPaths = Access.canUndoDelete(getApplication())
        val songs = try {
            withContext(Dispatchers.IO) { musicApp.repository.scan(trustPaths) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (generation == scanGeneration) {
                _scanning.value = false
                tell(error.message ?: text(R.string.library_read_failed))
            }
            return
        }
        if (generation != scanGeneration) return
        val hidden = musicApp.trash.inflightKeys()
        val visible = if (hidden.isEmpty()) songs else songs.filter { it.key !in hidden }
        _library.value = visible
        _scanning.value = false
        restoreSession(visible)
    }

    private fun restoreSession(songs: List<Song>) {
        if (sessionRestored) return
        sessionRestored = true
        if (engine.queue.value.isNotEmpty()) return
        val session = musicApp.store.currentSession()
        if (session.keys.isEmpty()) return
        val byKey = songs.associateBy { it.key }
        val queue = session.keys.mapNotNull { byKey[it] }
        if (queue.isEmpty()) return
        engine.restore(
            PlaySnap(
                songs = queue,
                index = session.index.coerceIn(0, queue.lastIndex),
                positionMs = session.positionMs,
                playWhenReady = false,
                title = session.title.ifBlank { text(R.string.resume) },
            ),
        )
    }

    private fun pendingThen(advance: () -> Unit) {
        val song = engine.currentSong() ?: return
        if (marks.value[song.key] != Disposition.KEEP) {
            musicApp.store.setMarks(listOf(song.key), Disposition.PENDING)
        }
        advance()
    }

    private fun presentDeleteGesture(song: Song) {
        _deleteGesture.value = gestureFor(song)
    }

    private fun commitGestureSongDelete() {
        val request = _deleteGesture.value ?: return
        val song = engine.currentSong()
        _deleteGesture.value = null
        if (song == null || song.key != request.songKey) {
            tell(text(R.string.song_not_playing))
            return
        }
        delete(listOf(song), text(R.string.deleted_named, song.title))
    }

    private fun commitGestureAlbumDelete() {
        val request = _deleteGesture.value ?: return
        if (request.albumSongs.isEmpty()) {
            tell(text(R.string.album_all_kept))
            return
        }
        _deleteGesture.value = null
        delete(request.albumSongs, request.doneLabel)
    }

    private fun requestAlbumDelete(song: Song) {
        val album = albumSongs(song)
        val plan = planAlbumDelete(album, marks.value)
        val title = song.displayAlbum(getApplication())
        if (plan.removable.isEmpty()) {
            tell(text(R.string.album_all_kept_named, title, album.size))
            return
        }
        if (!plan.needsConfirm) {
            delete(plan.removable, albumDoneLabel(title, plan))
            return
        }
        _playerOpen.value = true
        _deleteGesture.value = gestureFor(song)
    }

    private fun gestureFor(song: Song): DeleteGesture {
        val album = albumSongs(song)
        val plan = planAlbumDelete(album, marks.value)
        val albumTitle = song.displayAlbum(getApplication())
        val detail = when {
            plan.removable.isEmpty() -> text(R.string.album_swipe_none)
            plan.needsConfirm -> albumDeleteText(album.size, plan)
            else -> text(R.string.album_swipe_count, plan.removable.size)
        }
        return DeleteGesture(
            songKey = song.key,
            songTitle = song.title,
            albumTitle = albumTitle,
            detail = detail,
            albumSongs = plan.removable,
            albumTotal = album.size,
            kept = plan.kept,
            doneLabel = if (plan.removable.isEmpty()) "" else albumDoneLabel(albumTitle, plan),
        )
    }

    private fun albumSongs(song: Song): List<Song> {
        val matches = _library.value.filter { it.albumKey == song.albumKey }
        return matches.ifEmpty { listOf(song) }
    }

    private fun quickTitle(allow: Set<Disposition>?, mode: QueueMode): String {
        val who = when (allow) {
            null -> text(R.string.all)
            setOf(Disposition.NONE) -> text(R.string.unmarked)
            setOf(Disposition.PENDING) -> text(R.string.pending)
            setOf(Disposition.KEEP) -> text(R.string.keep)
            setOf(Disposition.NONE, Disposition.PENDING) -> text(R.string.unmarked_and_pending)
            else -> text(R.string.filtered)
        }
        val how = if (mode == QueueMode.ALBUM_SHUFFLE) text(R.string.album_shuffle) else text(R.string.song_shuffle)
        return text(R.string.dot_join, who, how)
    }

    private fun albumDeleteText(total: Int, plan: com.hiwtata.melique.data.AlbumDeletePlan): String {
        val removing = plan.removable.size
        val unmarked = removing - plan.pending
        return when (albumDeletePhrase(plan)) {
            AlbumDeletePhrase.PendingKept -> text(R.string.album_delete_pending_kept, total, plan.pending, plan.kept)
            AlbumDeletePhrase.Mixed -> text(R.string.album_delete_mixed, total, removing, unmarked, plan.pending, plan.kept)
            AlbumDeletePhrase.UnmarkedKept -> text(R.string.album_delete_unmarked_kept, total, removing, plan.kept)
            AlbumDeletePhrase.AllPending -> text(R.string.album_delete_all_pending, total, plan.pending)
        }
    }

    private fun albumDoneLabel(album: String, plan: com.hiwtata.melique.data.AlbumDeletePlan): String {
        val count = plan.removable.size
        return if (plan.kept > 0) {
            text(R.string.album_deleted_kept, album, count, plan.kept)
        } else {
            text(R.string.album_deleted_all, album, count)
        }
    }

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun tell(message: String) {
        _messages.tryEmit(message)
    }

    private data class PendingSystemDelete(val keys: Set<String>, val label: String)

    private data class DeleteRollback(val token: Long, val snap: PlaySnap, val next: PlaySnap)

    private companion object {
        const val REFRESH_QUIET_MS = 400L
        const val REFRESH_MIN_GAP_MS = 1_000L
    }
}

data class DeleteGesture(
    val songKey: String,
    val songTitle: String,
    val albumTitle: String,
    val detail: String,
    val albumSongs: List<Song>,
    val albumTotal: Int,
    val kept: Int,
    val doneLabel: String,
)
