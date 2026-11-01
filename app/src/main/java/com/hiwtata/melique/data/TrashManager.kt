package com.hiwtata.melique.data

import android.content.Context
import com.hiwtata.melique.R
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

class TrashManager(
    private val context: Context,
    private val store: LibraryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trashDir = File(context.filesDir, "trash").apply { mkdirs() }
    private val jobs = mutableMapOf<Long, Job>()

    private val _batches = MutableStateFlow<List<TrashBatch>>(emptyList())
    val batches: StateFlow<List<TrashBatch>> = _batches.asStateFlow()

    init {
        // Process death forfeits the undo window. Finish anything left in the private trash.
        scope.launch(Dispatchers.IO) {
            trashDir.listFiles()?.forEach { file -> file.delete() }
        }
    }

    fun moveToTrash(songs: List<Song>, label: String): Result<TrashBatch> {
        if (songs.isEmpty()) return Result.failure(IllegalArgumentException(context.getString(R.string.nothing_to_delete)))
        val missing = songs.filter { it.path.isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return Result.failure(IllegalStateException(context.getString(R.string.missing_path, missing.size)))
        }
        val token = SystemClock.elapsedRealtime()
        val moved = mutableListOf<TrashedItem>()
        try {
            songs.forEachIndexed { index, song ->
                val source = File(song.path!!)
                if (!source.exists()) throw IOException(context.getString(R.string.file_missing, song.title))
                val dest = File(trashDir, "${token}_${index}_${source.name}")
                if (!moveFile(source, dest)) {
                    dest.delete()
                    throw IOException(context.getString(R.string.cannot_move, song.title))
                }
                moved += TrashedItem(song, source.absolutePath, dest.absolutePath)
            }
        } catch (error: Exception) {
            moved.forEach { item ->
                moveFile(File(item.trashPath), File(item.originalPath))
            }
            return Result.failure(error)
        }
        moved.forEach { item ->
            MediaScannerConnection.scanFile(context, arrayOf(item.originalPath), null, null)
        }
        val batch = TrashBatch(
            token = token,
            label = label,
            items = moved,
            armedAtElapsed = SystemClock.elapsedRealtime(),
        )
        return Result.success(batch)
    }

    fun arm(batch: TrashBatch) {
        _batches.update { current -> current + batch }
        jobs[batch.token] = scope.launch {
            delay(UNDO_WINDOW_MS)
            commit(batch.token)
        }
    }

    fun takeLatest(): TrashBatch? {
        val batch = _batches.value.lastOrNull() ?: return null
        jobs.remove(batch.token)?.cancel()
        _batches.update { current -> current.filterNot { it.token == batch.token } }
        return batch
    }

    fun restoreFiles(batch: TrashBatch): Result<List<Song>> {
        val restored = mutableListOf<Song>()
        val rolled = mutableListOf<TrashedItem>()
        try {
            for (item in batch.items) {
                val original = File(item.originalPath)
                if (original.exists()) throw IOException(context.getString(R.string.original_exists, item.song.title))
                original.parentFile?.mkdirs()
                if (!moveFile(File(item.trashPath), original)) {
                    throw IOException(context.getString(R.string.cannot_undo_named, item.song.title))
                }
                rolled += item
                val fileUri = Uri.fromFile(original).toString()
                restored += item.song.copy(uriString = fileUri)
            }
        } catch (error: Exception) {
            rolled.forEach { item ->
                moveFile(File(item.originalPath), File(item.trashPath))
            }
            return Result.failure(error)
        }
        val paths = restored.mapNotNull { it.path }.toTypedArray()
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, paths, null, null)
        }
        return Result.success(restored)
    }

    private fun commit(token: Long) {
        val batch = _batches.value.firstOrNull { it.token == token } ?: return
        jobs.remove(token)
        _batches.update { current -> current.filterNot { it.token == token } }
        scope.launch(Dispatchers.IO) {
            batch.items.forEach { item -> File(item.trashPath).delete() }
        }
        store.clearMarks(batch.items.map { it.song.key })
    }

    private fun moveFile(source: File, dest: File): Boolean {
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

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
