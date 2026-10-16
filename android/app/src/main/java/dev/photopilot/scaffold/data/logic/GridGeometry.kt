package dev.photopilot.scaffold.data.logic

object GridGeometry {

    fun columns(availableWidthPx: Int, gapPx: Int, cellMinPx: Int): Int {
        if (availableWidthPx <= 0 || cellMinPx <= 0) return 1
        return maxOf(1, (availableWidthPx + gapPx) / (cellMinPx + gapPx))
    }

    fun columns(
        availableWidthPx: Int,
        gapPx: Int,
        cellMinPx: Int,
        compactCellMinPx: Int,
        compactBreakpointPx: Int,
    ): Int {
        val min = if (availableWidthPx < compactBreakpointPx) compactCellMinPx else cellMinPx
        return columns(availableWidthPx, gapPx, min)
    }
}
