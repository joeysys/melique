package com.hiwtata.melique

import com.hiwtata.melique.ui.FolderCovers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderCoversTest {
    @Test
    fun prefersCoverJpgThenTheOtherNames() {
        val entries = listOf("notes.txt", "Front.PNG", "Folder.JPG", "Cover.jpg", "front.jpg")
        assertEquals("Cover.jpg", FolderCovers.match(entries))
    }

    @Test
    fun jpgBeatsPngAndFolderBeatsFront() {
        assertEquals("folder.jpg", FolderCovers.match(listOf("front.png", "folder.jpg", "cover.png")))
        assertEquals("cover.png", FolderCovers.match(listOf("cover.png", "folder.png")))
        assertEquals("FRONT.PNG", FolderCovers.match(listOf("FRONT.PNG")))
    }

    @Test
    fun ignoresUnrelatedImages() {
        assertNull(FolderCovers.match(listOf("album.jpg", "cover.jpeg", "back.png")))
        assertEquals(0, FolderCovers.rank("Cover.JPG"))
        assertEquals(5, FolderCovers.rank("front.png"))
        assertEquals(-1, FolderCovers.rank("cover.jpeg"))
    }
}