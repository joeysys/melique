package com.hiwtata.melique

import android.app.Application
import com.hiwtata.melique.data.LibraryStore
import com.hiwtata.melique.data.MusicRepository
import com.hiwtata.melique.data.TrashManager
import com.hiwtata.melique.player.PlaybackEngine

class MusicApp : Application() {
    lateinit var engine: PlaybackEngine
        private set
    lateinit var store: LibraryStore
        private set
    lateinit var repository: MusicRepository
        private set
    lateinit var trash: TrashManager
        private set

    override fun onCreate() {
        super.onCreate()
        store = LibraryStore(this)
        repository = MusicRepository(this)
        trash = TrashManager(this, store)
        engine = PlaybackEngine(this, store)
    }
}
