package com.librevision.domain.commands

/** Feature-independent routing decision: Start opens Footpath from any screen. */
enum class ScanCommandAction {
    OPEN_FOOTPATH_AND_START,
    STOP,
    NONE,
}

fun Command.scanAction(): ScanCommandAction =
    when (this) {
        Command.START_SCANNING -> ScanCommandAction.OPEN_FOOTPATH_AND_START
        Command.STOP_SCANNING -> ScanCommandAction.STOP
        else -> ScanCommandAction.NONE
    }
