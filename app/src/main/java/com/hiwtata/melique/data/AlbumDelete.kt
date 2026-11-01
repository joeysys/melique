package com.hiwtata.melique.data

data class AlbumDeletePlan(
    val removable: List<Song>,
    val kept: Int,
    val pending: Int,
) {
    val needsConfirm: Boolean get() = kept > 0 || pending > 0
}

fun planAlbumDelete(songs: List<Song>, marks: Map<String, Disposition>): AlbumDeletePlan {
    val distinct = songs.distinctBy { it.key }
    val removable = distinct.filter { marks[it.key] != Disposition.KEEP }
    val pending = removable.count { marks[it.key] == Disposition.PENDING }
    return AlbumDeletePlan(
        removable = removable,
        kept = distinct.size - removable.size,
        pending = pending,
    )
}

enum class AlbumDeletePhrase {
    PendingKept,
    Mixed,
    UnmarkedKept,
    AllPending,
}

fun albumDeletePhrase(plan: AlbumDeletePlan): AlbumDeletePhrase {
    val unmarked = plan.removable.size - plan.pending
    return when {
        plan.kept > 0 && plan.pending > 0 && unmarked == 0 -> AlbumDeletePhrase.PendingKept
        plan.kept > 0 && plan.pending > 0 -> AlbumDeletePhrase.Mixed
        plan.kept > 0 -> AlbumDeletePhrase.UnmarkedKept
        else -> AlbumDeletePhrase.AllPending
    }
}
