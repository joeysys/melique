package com.hiwtata.melique.data

import android.content.Context
import com.hiwtata.melique.R
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

data class TrashedItem(
    val song: Song,
    val originalPath: String,
    val trashPath: String,
)

data class TrashBatch(
    val token: Long,
    val label: String,
    val items: List<TrashedItem>,
    val armedAtElapsed: Long,
)

data class TrashFailure(
    val token: Long,
    val songs: List<Song>,
    val message: String,
)

class TrashManager internal constructor(
    private val trashDir: File,
    private val strings: TrashStrings,
    private val clearMarks: (Collection<String>) -> Unit,
    private val undoWindowMs: Long,
    private val relocate: (File, File) -> Boolean,
    private val notifyScanner: (List<String>) -> Unit,
    private val scope: CoroutineScope,
) {
    constructor(context: Context, store: LibraryStore) : this(
        trashDir = File(context.filesDir, "trash").apply { mkdirs() },
        strings = TrashStrings.from(context),
        clearMarks = { keys -> store.clearMarks(keys) },
        undoWindowMs = UNDO_WINDOW_MS,
        relocate = ::relocateFile,
        notifyScanner = { paths -> notifyMediaScanner(context, paths) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    private val lock = Any()
    private val tokens = AtomicLong(SystemClock.elapsedRealtime())
    private val inflight = mutableMapOf<Long, InFlight>()

    private val _batches = MutableStateFlow<List<TrashBatch>>(emptyList())
    val batches: StateFlow<List<TrashBatch>> = _batches.asStateFlow()

    private val _failures = MutableSharedFlow<TrashFailure>(extraBufferCapacity = 8)
    val failures: SharedFlow<TrashFailure> = _failures.asSharedFlow()

    init {
        // Process death forfeits the undo window. Finish anything left in the private trash.
        scope.launch(Dispatchers.IO) {
            trashDir.listFiles()?.forEach { file -> file.delete() }
        }
    }

    /** Keys hidden from library scans until the delete commits, fails, or is undone. */
    fun inflightKeys(): Set<String> = synchronized(lock) {
        inflight.values
            .asSequence()
            .filter { it.phase == Phase.MOVING || it.phase == Phase.UNDOING }
            .flatMap { it.songs.asSequence() }
            .map { it.key }
            .toSet()
    }

    /**
     * Validate and show the undo batch immediately. File moves start only after [startDelete],
     * so the caller can drop open file handles first.
     */
    fun stageDelete(songs: List<Song>, label: String): Result<Long> {
        if (songs.isEmpty()) {
            return Result.failure(IllegalArgumentException(strings.nothingToDelete))
        }
        val missing = songs.count { it.path.isNullOrBlank() }
        if (missing > 0) {
            return Result.failure(IllegalStateException(strings.missingPath(missing)))
        }
        val token = nextToken()
        val now = SystemClock.elapsedRealtime()
        val active = InFlight(
            token = token,
            label = label,
            songs = songs,
            armedAtElapsed = now,
        )
        synchronized(lock) {
            inflight[token] = active
            _batches.update { current -> current + TrashBatch(token, label, emptyList(), now) }
        }
        return Result.success(token)
    }

    fun startDelete(token: Long) {
        val active = synchronized(lock) {
            val active = inflight[token] ?: return
            if (active.started || active.phase != Phase.MOVING) return
            active.started = true
            active
        }
        active.windowJob = scope.launch(Dispatchers.IO) {
            delay(undoWindowMs)
            val proceed = synchronized(lock) {
                if (active.phase != Phase.MOVING || active.undoClosed) {
                    false
                } else {
                    active.undoClosed = true
                    _batches.update { current -> current.filterNot { it.token == active.token } }
                    true
                }
            }
            if (!proceed) return@launch
            active.moveDone.await()
            commit(active)
        }
        scope.launch(Dispatchers.IO) {
            try {
                moveAll(active)
            } finally {
                active.moveDone.complete(Unit)
            }
        }
    }

    /** Hide the undo strip immediately, then move files back without blocking the caller thread. */
    suspend fun undoLatest(): Result<List<Song>>? {
        val active = synchronized(lock) {
            val batch = _batches.value.lastOrNull()
            val current = batch?.let { inflight[it.token] }
            if (batch == null || current == null || current.phase != Phase.MOVING || current.undoClosed) {
                null
            } else {
                current.phase = Phase.UNDOING
                current.windowJob?.cancel()
                _batches.update { list -> list.filterNot { it.token == current.token } }
                current
            }
        } ?: return null
        val result = CompletableDeferred<Result<List<Song>>>()
        scope.launch(Dispatchers.IO) {
            try {
                result.complete(recover(active))
            } catch (error: Throwable) {
                if (!result.isCompleted) result.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        return result.await()
    }

    private suspend fun recover(active: InFlight): Result<List<Song>> {
        if (!active.started) {
            if (!active.moveDone.isCompleted) active.moveDone.complete(Unit)
            synchronized(lock) { inflight.remove(active.token) }
            return Result.success(active.songs)
        }
        active.moveDone.await()
        val moved = synchronized(lock) { active.moved.toList() }
        val restored = restoreFiles(
            TrashBatch(active.token, active.label, moved, active.armedAtElapsed),
        )
        if (restored.isFailure) {
            rearm(active, moved)
            return Result.failure(
                restored.exceptionOrNull() ?: IllegalStateException(strings.undoFailed),
            )
        }
        synchronized(lock) { inflight.remove(active.token) }
        val byKey = restored.getOrThrow().associateBy { it.key }
        return Result.success(active.songs.map { byKey[it.key] ?: it })
    }

    private fun moveAll(active: InFlight) {
        try {
            active.songs.forEachIndexed { index, song ->
                if (synchronized(lock) { active.phase != Phase.MOVING }) return
                val source = File(song.path!!)
                if (!source.exists()) throw IOException(strings.fileMissing(song.title))
                val dest = File(trashDir, "${active.token}_${index}_${source.name}")
                if (!relocate(source, dest)) {
                    dest.delete()
                    throw IOException(strings.cannotMove(song.title))
                }
                synchronized(lock) {
                    active.moved += TrashedItem(song, source.absolutePath, dest.absolutePath)
                }
            }
            val paths = synchronized(lock) {
                if (active.phase != Phase.MOVING) return
                active.moved.map { it.originalPath }
            }
            scanPaths(paths)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(active, error)
        }
    }

    private fun fail(active: InFlight, error: Exception) {
        val moved = synchronized(lock) {
            if (active.phase != Phase.MOVING) return
            active.phase = Phase.FAILED
            active.windowJob?.cancel()
            _batches.update { current -> current.filterNot { it.token == active.token } }
            inflight.remove(active.token)
            active.moved.toList()
        }
        moved.asReversed().forEach { item ->
            relocate(File(item.trashPath), File(item.originalPath))
        }
        _failures.tryEmit(
            TrashFailure(
                token = active.token,
                songs = active.songs,
                message = error.message ?: strings.deleteFailed,
            ),
        )
    }

    private fun rearm(active: InFlight, moved: List<TrashedItem>) {
        synchronized(lock) {
            active.phase = Phase.MOVING
            active.undoClosed = false
            active.moved.clear()
            active.moved.addAll(moved)
            active.armedAtElapsed = SystemClock.elapsedRealtime()
            inflight[active.token] = active
            _batches.update { current ->
                current + TrashBatch(active.token, active.label, moved, active.armedAtElapsed)
            }
        }
        active.windowJob = scope.launch(Dispatchers.IO) {
            delay(undoWindowMs)
            val proceed = synchronized(lock) {
                if (active.phase != Phase.MOVING || active.undoClosed) {
                    false
                } else {
                    active.undoClosed = true
                    _batches.update { current -> current.filterNot { it.token == active.token } }
                    true
                }
            }
            if (proceed) commit(active)
        }
    }

    private fun commit(active: InFlight) {
        val moved = synchronized(lock) {
            if (active.phase != Phase.MOVING || !active.undoClosed) return
            active.phase = Phase.DONE
            inflight.remove(active.token)
            active.moved.toList()
        }
        moved.forEach { item -> File(item.trashPath).delete() }
        clearMarks(active.songs.map { it.key })
    }

    private fun restoreFiles(batch: TrashBatch): Result<List<Song>> {
        val restored = mutableListOf<Song>()
        val rolled = mutableListOf<TrashedItem>()
        try {
            for (item in batch.items) {
                val original = File(item.originalPath)
                if (original.exists()) throw IOException(strings.originalExists(item.song.title))
                original.parentFile?.mkdirs()
                if (!relocate(File(item.trashPath), original)) {
                    throw IOException(strings.cannotUndo(item.song.title))
                }
                rolled += item
                val uri = Uri.fromFile(original)?.toString() ?: original.toURI().toString()
                restored += item.song.copy(uriString = uri)
            }
        } catch (error: Exception) {
            rolled.asReversed().forEach { item ->
                relocate(File(item.originalPath), File(item.trashPath))
            }
            return Result.failure(error)
        }
        scanPaths(restored.mapNotNull { it.path })
        return Result.success(restored)
    }

    private fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        notifyScanner(paths.distinct())
    }

    private fun nextToken(): Long {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val prev = tokens.get()
            val token = if (now > prev) now else prev + 1
            if (tokens.compareAndSet(prev, token)) return token
        }
    }

    private enum class Phase {
        MOVING,
        UNDOING,
        FAILED,
        DONE,
    }

    private class InFlight(
        val token: Long,
        val label: String,
        val songs: List<Song>,
        var armedAtElapsed: Long,
    ) {
        val moved = mutableListOf<TrashedItem>()
        var phase = Phase.MOVING
        var undoClosed = false
        var started = false
        var windowJob: Job? = null
        val moveDone = CompletableDeferred<Unit>()
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}

internal class TrashStrings(
    val nothingToDelete: String,
    val missingPath: (Int) -> String,
    val fileMissing: (String) -> String,
    val cannotMove: (String) -> String,
    val deleteFailed: String,
    val undoFailed: String,
    val originalExists: (String) -> String,
    val cannotUndo: (String) -> String,
) {
    companion object {
        fun from(context: Context) = TrashStrings(
            nothingToDelete = context.getString(R.string.nothing_to_delete),
            missingPath = { count -> context.getString(R.string.missing_path, count) },
            fileMissing = { title -> context.getString(R.string.file_missing, title) },
            cannotMove = { title -> context.getString(R.string.cannot_move, title) },
            deleteFailed = context.getString(R.string.delete_failed),
            undoFailed = context.getString(R.string.undo_failed),
            originalExists = { title -> context.getString(R.string.original_exists, title) },
            cannotUndo = { title -> context.getString(R.string.cannot_undo_named, title) },
        )
    }
}

internal fun relocateFile(source: File, dest: File): Boolean {
    if (!source.exists()) return false
    dest.parentFile?.mkdirs()
    try {
        Files.move(
            source.toPath(),
            dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        return true
    } catch (_: IOException) {
        return try {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (source.length() > 0 && dest.length() != source.length()) {
                dest.delete()
                false
            } else if (!source.delete()) {
                dest.delete()
                false
            } else {
                true
            }
        } catch (_: IOException) {
            dest.delete()
            false
        }
    }
}

internal fun notifyMediaScanner(context: Context, paths: List<String>) {
    if (paths.isEmpty()) return
    paths.distinct().chunked(80).forEach { chunk ->
        runCatching {
            MediaScannerConnection.scanFile(context, chunk.toTypedArray(), null, null)
        }
    }
}
