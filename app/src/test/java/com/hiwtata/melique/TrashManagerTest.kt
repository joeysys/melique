package com.hiwtata.melique

import com.hiwtata.melique.data.Song
import com.hiwtata.melique.data.TrashManager
import com.hiwtata.melique.data.TrashStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TrashManagerTest {
    @Test
    fun undoWhileMovingRestoresFilesEvenIfCallerIsCancelled() = runBlocking {
        val root = tempRoot()
        val original = File(root, "music/a.mp3").apply {
            parentFile?.mkdirs()
            writeText("a")
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = manager(root, undoWindowMs = 5_000) { source, dest ->
            if (source.name == "a.mp3" && source.parentFile?.name == "music") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            com.hiwtata.melique.data.relocateFile(source, dest)
        }
        val token = manager.stageDelete(listOf(song(original)), "album").getOrThrow()
        manager.startDelete(token)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            manager.undoLatest()
        }
        withTimeout(2_000) {
            while (manager.batches.value.isNotEmpty()) delay(15)
        }
        caller.cancel()
        release.countDown()
        withTimeout(5_000) {
            while (!original.exists() || manager.inflightKeys().isNotEmpty()) delay(20)
        }
        assertTrue(original.readText() == "a")
        assertTrue(File(root, "trash").listFiles().isNullOrEmpty())
    }

    @Test
    fun moveFailureAfterWindowStillRollsFilesBack() = runBlocking {
        val root = tempRoot()
        val first = File(root, "music/a.mp3").apply {
            parentFile?.mkdirs()
            writeText("a")
        }
        val second = File(root, "music/b.mp3").apply { writeText("b") }
        val release = CountDownLatch(1)
        val manager = manager(root, undoWindowMs = 40) { source, dest ->
            if (source.name == "a.mp3") {
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            if (source.name == "b.mp3") return@manager false
            com.hiwtata.melique.data.relocateFile(source, dest)
        }
        val failure = async { manager.failures.first() }
        delay(30)
        val token = manager.stageDelete(listOf(song(first), song(second)), "album").getOrThrow()
        manager.startDelete(token)
        delay(80)
        release.countDown()
        val reported = withTimeout(5_000) { failure.await() }
        assertEquals(token, reported.token)
        assertEquals(listOf("a", "b"), reported.songs.map { it.key })
        assertTrue(first.readText() == "a")
        assertTrue(second.readText() == "b")
        assertTrue(manager.inflightKeys().isEmpty())
        assertTrue(manager.batches.value.isEmpty())
    }

    @Test
    fun failedRestoreRearmsAndLaterCommits() = runBlocking {
        val root = tempRoot()
        val original = File(root, "music/a.mp3").apply {
            parentFile?.mkdirs()
            writeText("a")
        }
        val blockRestore = AtomicBoolean(false)
        val cleared = mutableListOf<String>()
        val manager = manager(
            root = root,
            undoWindowMs = 400,
            clearMarks = { keys -> cleared += keys },
        ) { source, dest ->
            if (blockRestore.get() && dest.name == "a.mp3") return@manager false
            com.hiwtata.melique.data.relocateFile(source, dest)
        }
        val token = manager.stageDelete(listOf(song(original)), "album").getOrThrow()
        manager.startDelete(token)
        withTimeout(5_000) {
            while (original.exists()) delay(15)
        }
        blockRestore.set(true)
        val undone = manager.undoLatest()
        assertTrue(undone!!.isFailure)
        assertFalse(original.exists())
        assertEquals(1, manager.batches.value.size)
        withTimeout(5_000) {
            while (manager.batches.value.isNotEmpty() || cleared.isEmpty()) delay(20)
        }
        assertEquals(listOf("a"), cleared)
        assertTrue(File(root, "trash").listFiles().isNullOrEmpty())
    }

    private fun manager(
        root: File,
        undoWindowMs: Long,
        clearMarks: (Collection<String>) -> Unit = {},
        relocate: (File, File) -> Boolean,
    ): TrashManager {
        return TrashManager(
            trashDir = File(root, "trash").apply { mkdirs() },
            strings = TrashStrings(
                nothingToDelete = "nothing",
                missingPath = { "missing $it" },
                fileMissing = { "missing $it" },
                cannotMove = { "cannot move $it" },
                deleteFailed = "delete failed",
                undoFailed = "undo failed",
                originalExists = { "exists $it" },
                cannotUndo = { "cannot undo $it" },
            ),
            clearMarks = clearMarks,
            undoWindowMs = undoWindowMs,
            relocate = relocate,
            notifyScanner = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun song(file: File) = Song(
        id = 1,
        key = file.nameWithoutExtension,
        title = file.name,
        artist = "artist",
        album = "Night",
        albumKey = "Night",
        albumId = 1,
        durationMs = 1,
        track = 1,
        disc = 1,
        uriString = file.toURI().toString(),
        path = file.absolutePath,
    )

    private fun tempRoot(): File {
        return File("build/tmp/trash-tests/${System.nanoTime()}").apply { mkdirs() }
    }
}
