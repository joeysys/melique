package com.hiwtata.melique.ui

enum class CoverInput {
    Tap,
    LongPress,
    Left,
    Right,
    Up,
    Down,
}

enum class CoverAction {
    ToggleMode,
    KeepAndAdvance,
    Next,
    Previous,
    PendingAndNext,
    PendingAndPrevious,
    OpenDelete,
    DeleteSong,
    DeleteAlbum,
    CancelDelete,
    OpenQueue,
    Collapse,
    Ignore,
}

fun interpretCover(
    input: CoverInput,
    selecting: Boolean,
    confirming: Boolean,
    kept: Boolean,
): CoverAction {
    if (confirming) {
        return when (input) {
            CoverInput.Left -> CoverAction.DeleteSong
            CoverInput.Right -> CoverAction.DeleteAlbum
            CoverInput.Down -> CoverAction.CancelDelete
            else -> CoverAction.Ignore
        }
    }
    if (selecting) {
        return when (input) {
            CoverInput.Tap -> CoverAction.Ignore
            CoverInput.LongPress -> CoverAction.KeepAndAdvance
            CoverInput.Left -> if (kept) CoverAction.Next else CoverAction.PendingAndNext
            CoverInput.Right -> if (kept) CoverAction.Previous else CoverAction.PendingAndPrevious
            CoverInput.Up -> CoverAction.OpenDelete
            CoverInput.Down -> CoverAction.Collapse
        }
    }
    return when (input) {
        CoverInput.Tap -> CoverAction.Ignore
        CoverInput.LongPress -> CoverAction.Ignore
        CoverInput.Left -> CoverAction.Next
        CoverInput.Right -> CoverAction.Previous
        CoverInput.Up -> CoverAction.OpenQueue
        CoverInput.Down -> CoverAction.Collapse
    }
}
