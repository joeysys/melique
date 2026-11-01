package com.hiwtata.melique.ui

internal object FolderCovers {
    val names: List<String> = listOf(
        "cover.jpg",
        "folder.jpg",
        "front.jpg",
        "cover.png",
        "folder.png",
        "front.png",
    )

    /** First directory entry matching [names], jpg before png, cover before folder before front. */
    fun match(entries: List<String>): String? {
        val byLower = HashMap<String, String>(entries.size)
        for (name in entries) {
            byLower.putIfAbsent(name.lowercase(), name)
        }
        for (wanted in names) {
            byLower[wanted]?.let { return it }
        }
        return null
    }

    fun rank(fileName: String): Int = names.indexOf(fileName.lowercase())
}
