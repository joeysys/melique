package com.hiwtata.melique.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val lock = Any()

    private val _marks = MutableStateFlow<Map<String, Disposition>>(emptyMap())
    val marks: StateFlow<Map<String, Disposition>> = _marks.asStateFlow()

    private val _playlists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()

    private var session = SessionState()

    init {
        load()
    }

    fun currentSession(): SessionState = synchronized(lock) { session }

    fun setMarks(keys: Collection<String>, disposition: Disposition) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val next = _marks.value.toMutableMap()
            for (key in keys) {
                if (disposition == Disposition.NONE) next.remove(key) else next[key] = disposition
            }
            _marks.value = next
            persistLocked()
        }
    }

    fun clearMarks(keys: Collection<String>) = setMarks(keys, Disposition.NONE)

    fun savePlaylist(name: String, songKeys: List<String>): SavedPlaylist? {
        if (songKeys.isEmpty()) return null
        return synchronized(lock) {
            val unique = uniqueName(name)
            val playlist = SavedPlaylist(
                id = System.currentTimeMillis().toString(16) + songKeys.size,
                name = unique,
                songKeys = songKeys,
                createdAt = System.currentTimeMillis(),
            )
            _playlists.value = listOf(playlist) + _playlists.value
            persistLocked()
            playlist
        }
    }

    fun deletePlaylist(id: String) {
        synchronized(lock) {
            _playlists.value = _playlists.value.filterNot { it.id == id }
            persistLocked()
        }
    }

    fun saveSession(state: SessionState) {
        synchronized(lock) {
            session = state
            persistLocked()
        }
    }

    private fun uniqueName(name: String): String {
        val existing = _playlists.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index++
        return "$name $index"
    }

    private fun load() {
        synchronized(lock) {
            if (!file.exists()) return
            try {
                val root = JSONObject(file.readText())
                val marksJson = root.optJSONObject("marks") ?: JSONObject()
                val marks = mutableMapOf<String, Disposition>()
                for (key in marksJson.keys()) {
                    val parsed = runCatching { Disposition.valueOf(marksJson.getString(key)) }.getOrNull()
                    if (parsed != null && parsed != Disposition.NONE) marks[key] = parsed
                }
                _marks.value = marks
                val lists = root.optJSONArray("playlists") ?: JSONArray()
                _playlists.value = buildList {
                    for (i in 0 until lists.length()) {
                        val item = lists.optJSONObject(i) ?: continue
                        val keys = item.optJSONArray("keys") ?: JSONArray()
                        add(
                            SavedPlaylist(
                                id = item.optString("id"),
                                name = item.optString("name"),
                                songKeys = buildList {
                                    for (k in 0 until keys.length()) add(keys.optString(k))
                                }.filter { it.isNotBlank() },
                                createdAt = item.optLong("createdAt"),
                            ),
                        )
                    }
                }
                val sessionJson = root.optJSONObject("session")
                if (sessionJson != null) {
                    val keys = sessionJson.optJSONArray("keys") ?: JSONArray()
                    session = SessionState(
                        title = sessionJson.optString("title"),
                        keys = buildList {
                            for (i in 0 until keys.length()) add(keys.optString(i))
                        }.filter { it.isNotBlank() },
                        index = sessionJson.optInt("index"),
                        positionMs = sessionJson.optLong("positionMs"),
                    )
                }
            } catch (_: Exception) {
                _marks.value = emptyMap()
                _playlists.value = emptyList()
                session = SessionState()
            }
        }
    }

    private fun persistLocked() {
        val root = JSONObject()
        val marksJson = JSONObject()
        _marks.value.forEach { (key, value) -> marksJson.put(key, value.name) }
        root.put("marks", marksJson)
        val lists = JSONArray()
        _playlists.value.forEach { playlist ->
            lists.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("createdAt", playlist.createdAt)
                    .put("keys", JSONArray(playlist.songKeys)),
            )
        }
        root.put("playlists", lists)
        val keys = JSONArray()
        session.keys.forEach { keys.put(it) }
        root.put(
            "session",
            JSONObject()
                .put("title", session.title)
                .put("keys", keys)
                .put("index", session.index)
                .put("positionMs", session.positionMs),
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }
}
