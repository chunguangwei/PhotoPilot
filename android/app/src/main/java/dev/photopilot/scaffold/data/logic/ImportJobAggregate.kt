package dev.photopilot.scaffold.data.logic

import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.db.ImportJobState

object ImportJobAggregate {

    val terminalItemStates: Set<ImportItemState> = setOf(
        ImportItemState.IMPORTED,
        ImportItemState.FAILED,
        ImportItemState.CANCELED,
        ImportItemState.SKIPPED,
    )

    enum class Signal { NONE, USER_CANCEL, FATAL_ERROR, USER_PAUSE, AUTO_PAUSE }

    fun derive(
        itemStates: Collection<ImportItemState>,
        signal: Signal,
        enumerationComplete: Boolean,
    ): ImportJobState {
        if (signal == Signal.USER_CANCEL) return ImportJobState.CANCELED
        if (signal == Signal.FATAL_ERROR) return ImportJobState.FAILED
        if (signal == Signal.USER_PAUSE) return ImportJobState.PAUSED
        if (signal == Signal.AUTO_PAUSE) return ImportJobState.PAUSED_AUTO
        if (itemStates.isEmpty()) {
            return if (enumerationComplete) ImportJobState.COMPLETED else ImportJobState.RUNNING
        }
        if (itemStates.any { it !in terminalItemStates }) return ImportJobState.RUNNING

        val imported = itemStates.count { it == ImportItemState.IMPORTED }
        val failed = itemStates.count { it == ImportItemState.FAILED }
        return when {
            failed > 0 && imported > 0 -> ImportJobState.PARTIAL_FAILED
            failed > 0 -> ImportJobState.FAILED
            else -> ImportJobState.COMPLETED
        }
    }

    fun countByState(itemStates: Collection<ImportItemState>): Map<ImportItemState, Int> =
        itemStates.groupingBy { it }.eachCount()
}
