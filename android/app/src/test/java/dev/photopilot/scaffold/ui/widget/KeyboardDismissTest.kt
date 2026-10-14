package dev.photopilot.scaffold.ui.widget

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardDismissTest {
    private val blank = KeyboardDismissGesture.Target()
    private val form = KeyboardDismissGesture.Target(scrollable = true)

    private fun gesture(target: KeyboardDismissGesture.Target = blank, imeVisible: Boolean = true): KeyboardDismissGesture =
        KeyboardDismissGesture(10f).apply { begin(100f, 100f, imeVisible, target) }

    @Test fun tapWaitsUntilUpAndIncludesSlopBoundary() {
        val state = gesture()
        assertFalse(state.move(106f, 108f))
        assertTrue(state.end(106f, 108f, blank))
        assertFalse(state.end(100f, 100f, blank))
    }

    @Test fun releaseOutsideSlopDoesNotDismiss() {
        assertFalse(gesture().end(111f, 100f, blank))
        assertFalse(gesture().end(108f, 108f, blank))
    }

    @Test fun movingOutThenBackCannotBecomeTap() {
        val state = gesture()
        assertFalse(state.move(120f, 100f))
        assertFalse(state.end(100f, 100f, blank))
    }

    @Test fun downwardVerticalFormDragDismissesExactlyOnce() {
        val state = gesture(form)
        assertFalse(state.move(100f, 110f))
        assertTrue(state.move(105f, 111f))
        assertFalse(state.move(105f, 130f))
        assertFalse(state.end(105f, 130f, blank))
    }

    @Test fun nonScrollableBackgroundDoesNotDismissOnDrag() {
        assertFalse(gesture().move(100f, 140f))
    }

    @Test fun horizontalUpwardAndDiagonalGesturesAreNotDownwardDrags() {
        for ((x, y) in listOf(140f to 105f, 100f to 80f, 120f to 120f)) {
            val state = gesture(form)
            assertFalse(state.move(x, y))
            assertFalse(state.move(100f, 160f))
            assertFalse(state.end(100f, 100f, blank))
        }
    }

    @Test fun protectedControlsRejectTapAndDragAtBothEndpoints() {
        val protected = listOf(
            form.copy(editText = true),
            form.copy(keepInput = true),
            form.copy(seekBar = true),
            form.copy(horizontal = true),
            form.copy(present = false),
        )
        for (target in protected) {
            assertFalse(gesture(target).end(100f, 100f, blank))
            assertFalse(gesture().end(100f, 100f, target))
            assertFalse(gesture(target).move(100f, 150f))
        }
    }

    @Test fun keyboardHiddenNeverRequestsDismissal() {
        assertFalse(gesture(imeVisible = false).end(100f, 100f, blank))
        assertFalse(gesture(form, imeVisible = false).move(100f, 150f))
    }

    @Test fun cancellationAndMultiplePointersCannotDismiss() {
        val state = gesture(form)
        state.cancel()
        assertFalse(state.move(100f, 150f))
        assertFalse(state.end(100f, 100f, blank))
        state.begin(0f, 0f, true, blank)
        assertTrue(state.end(0f, 0f, blank))
    }

    @Test fun focusSwitchCancelsTheRecordedInputGesture() {
        val state = gesture()
        state.cancel()
        assertFalse(state.end(100f, 100f, blank))
    }

    @Test fun imeAndSystemBarsUseMaximumNotSum() {
        assertEquals(276, KeyboardDismissGesture.imeBottomExtra(true, 300, 24))
        assertEquals(300, KeyboardDismissGesture.imeBottomExtra(true, 300, 0))
        assertEquals(0, KeyboardDismissGesture.imeBottomExtra(true, 24, 24))
        assertEquals(0, KeyboardDismissGesture.imeBottomExtra(true, 12, 24))
    }

    @Test fun closingFloatingImeAndRotationDoNotRetainExtraPadding() {
        assertEquals(276, KeyboardDismissGesture.imeBottomExtra(true, 300, 24))
        assertEquals(180, KeyboardDismissGesture.imeBottomExtra(true, 180, 0))
        assertEquals(0, KeyboardDismissGesture.imeBottomExtra(false, 300, 24))
        assertEquals(0, KeyboardDismissGesture.imeBottomExtra(true, 0, 24))
        assertEquals(0, KeyboardDismissGesture.imeBottomExtra(true, -1, 24))
    }

    @Test fun doneOnlyAppliesToUnassignedOrDoneSingleLineFields() {
        for (action in listOf(EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_DONE)) {
            assertTrue(KeyboardDismissGesture.supportsDone(true, action))
            assertFalse(KeyboardDismissGesture.supportsDone(false, action))
        }
        for (action in listOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND)) {
            assertFalse(KeyboardDismissGesture.supportsDone(true, action))
        }
    }
}
