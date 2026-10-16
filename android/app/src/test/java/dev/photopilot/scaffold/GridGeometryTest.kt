package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.GridGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class GridGeometryTest {

    @Test
    fun columnsFollowDesignFormula() {
        assertEquals(6, GridGeometry.columns(availableWidthPx = 1000, gapPx = 16, cellMinPx = 150))
        assertEquals(1, GridGeometry.columns(availableWidthPx = 200, gapPx = 16, cellMinPx = 150))
        assertEquals(2, GridGeometry.columns(availableWidthPx = 316, gapPx = 16, cellMinPx = 150))
        assertEquals(1, GridGeometry.columns(availableWidthPx = 315, gapPx = 16, cellMinPx = 150))
    }

    @Test
    fun degenerateInputsYieldSingleColumn() {
        assertEquals(1, GridGeometry.columns(0, 16, 150))
        assertEquals(1, GridGeometry.columns(100, 16, 0))
    }

    @Test
    fun compactWidthUsesCompactCellMin() {
        assertEquals(
            2,
            GridGeometry.columns(
                availableWidthPx = 300,
                gapPx = 16,
                cellMinPx = 150,
                compactCellMinPx = 100,
                compactBreakpointPx = 600,
            ),
        )
        assertEquals(
            6,
            GridGeometry.columns(
                availableWidthPx = 1000,
                gapPx = 16,
                cellMinPx = 150,
                compactCellMinPx = 100,
                compactBreakpointPx = 600,
            ),
        )
        assertEquals(
            3,
            GridGeometry.columns(
                availableWidthPx = 600,
                gapPx = 16,
                cellMinPx = 150,
                compactCellMinPx = 100,
                compactBreakpointPx = 600,
            ),
        )
    }
}
