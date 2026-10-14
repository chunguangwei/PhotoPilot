package dev.photopilot.scaffold.ui.widget

import android.app.Dialog
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.SeekBar
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

class KeyboardDismiss(private val root: ViewGroup) {

    private val gesture = KeyboardDismissGesture(ViewConfiguration.get(root.context).scaledTouchSlop.toFloat())
    private var startingInput: EditText? = null
    private var startingTarget: View? = null

    init {
        root.isFocusable = true
        root.isFocusableInTouchMode = true
    }

    fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startingInput = root.findFocus() as? EditText
                startingTarget = hitTarget(root, event.rawX, event.rawY)
                gesture.begin(
                    event.rawX,
                    event.rawY,
                    startingInput != null && imeVisible(root),
                    targetPolicy(startingTarget),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 || !sameInput()) {
                    cancel()
                    return
                }
                for (index in 0 until event.historySize) {
                    val x = event.getHistoricalX(index) + event.rawX - event.x
                    val y = event.getHistoricalY(index) + event.rawY - event.y
                    if (gesture.move(x, y)) dismissStartingInput()
                }
                if (gesture.move(event.rawX, event.rawY)) dismissStartingInput()
            }
            MotionEvent.ACTION_UP -> {
                if (sameInput() && gesture.end(event.rawX, event.rawY, targetPolicy(hitTarget(root, event.rawX, event.rawY)))) {
                    dismissStartingInput()
                }
                cancel()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancel()
        }
    }

    fun installMainWindowInsets(window: Window) {
        if (Build.VERSION.SDK_INT < 30) return
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.fitsSystemWindows = false
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val extra = KeyboardDismissGesture.imeBottomExtra(
                insets.isVisible(WindowInsets.Type.ime()),
                insets.getInsets(WindowInsets.Type.ime()).bottom,
                bars.bottom,
            )
            root.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom + extra)
            insets
        }
        root.requestApplyInsets()
    }

    private fun sameInput(): Boolean {
        val input = startingInput ?: return false
        return input.isShown && root.findFocus() === input && startingTarget?.isAttachedToWindow == true
    }

    private fun dismissStartingInput() {
        val input = startingInput ?: return
        if (sameInput()) dismiss(root, input)
    }

    private fun cancel() {
        gesture.cancel()
        startingInput = null
        startingTarget = null
    }

    private fun targetPolicy(target: View?): KeyboardDismissGesture.Target {
        var current = target
        var input = false
        var keepInput = false
        var seekBar = false
        var horizontal = false
        var scrollable = false
        while (current != null) {
            input = input || current is EditText
            keepInput = keepInput || keepInputViews.containsKey(current)
            seekBar = seekBar || current is SeekBar
            horizontal = horizontal || current is HorizontalScrollView || current.canScrollHorizontally(-1) || current.canScrollHorizontally(1)
            scrollable = scrollable || current is ScrollView || current is AbsListView || current.canScrollVertically(-1) || current.canScrollVertically(1)
            if (current === root) break
            current = current.parent as? View
        }
        return KeyboardDismissGesture.Target(target != null, input, keepInput, seekBar, horizontal, scrollable)
    }

    private fun hitTarget(view: View, x: Float, y: Float): View? {
        val rect = Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(rect)) return null
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        rect.offset(origin[0], origin[1])
        if (!rect.contains(x.toInt(), y.toInt())) return null
        if (view is ViewGroup) {
            val children = (0 until view.childCount).map { view.getChildAt(it) }
                .withIndex().sortedWith(compareByDescending<IndexedValue<View>> { it.value.z }.thenByDescending { it.index })
            for (child in children) {
                hitTarget(child.value, x, y)?.let { return it }
            }
        }
        return view
    }

    companion object {
        private val keepInputViews = WeakHashMap<View, Unit>()

        fun keepInputOnTouch(view: View) {
            keepInputViews[view] = Unit
        }

        fun configureDone(input: EditText) {
            val action = input.imeOptions and EditorInfo.IME_MASK_ACTION
            if (!KeyboardDismissGesture.supportsDone(input.maxLines == 1, action)) return
            input.imeOptions = (input.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or EditorInfo.IME_ACTION_DONE
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) {
                    false
                } else {
                    val root = input.rootView.findViewById<ViewGroup>(android.R.id.content)
                        ?: input.rootView as? ViewGroup
                    if (root != null) dismiss(root, input)
                    true
                }
            }
        }

        fun attachDialog(dialog: Dialog) {
            val window = dialog.window ?: return
            val root = window.decorView as? ViewGroup ?: return
            val previous = window.callback ?: return
            val helper = KeyboardDismiss(root)
            window.callback = object : Window.Callback by previous {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    helper.observe(event)
                    return previous.dispatchTouchEvent(event)
                }
            }
        }

        private fun dismiss(root: ViewGroup, input: EditText) {
            if (!input.hasFocus() || !imeVisible(root)) return
            val manager = root.context.getSystemService(InputMethodManager::class.java) ?: return
            manager.hideSoftInputFromWindow(input.windowToken, 0)
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            val focusability = root.descendantFocusability
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            try {
                root.requestFocus()
            } finally {
                root.descendantFocusability = focusability
            }
        }

        private fun imeVisible(root: View): Boolean {
            val insets = root.rootWindowInsets
            if (Build.VERSION.SDK_INT >= 30) return insets?.isVisible(WindowInsets.Type.ime()) == true
            val minimum = (100 * root.resources.displayMetrics.density).toInt()
            if (insets != null && insets.systemWindowInsetBottom - insets.stableInsetBottom > minimum) return true
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val decor = root.rootView
            val position = IntArray(2)
            decor.getLocationOnScreen(position)
            val hiddenBottom = position[1] + decor.height - visible.bottom
            return hiddenBottom - (insets?.stableInsetBottom ?: 0) > max(minimum, decor.height / 6)
        }
    }
}

internal class KeyboardDismissGesture(private val slop: Float) {

    data class Target(
        val present: Boolean = true,
        val editText: Boolean = false,
        val keepInput: Boolean = false,
        val seekBar: Boolean = false,
        val horizontal: Boolean = false,
        val scrollable: Boolean = false,
    ) {
        val allowsTap: Boolean get() = present && !editText && !keepInput && !seekBar && !horizontal
        val allowsDrag: Boolean get() = allowsTap && scrollable
    }

    private var startX = 0f
    private var startY = 0f
    private var active = false
    private var target = Target(present = false)
    private var moved = false
    private var directionDecided = false

    fun begin(x: Float, y: Float, inputWithIme: Boolean, target: Target) {
        startX = x
        startY = y
        active = inputWithIme
        this.target = target
        moved = false
        directionDecided = false
    }

    fun move(x: Float, y: Float): Boolean {
        if (!active) return false
        val dx = x - startX
        val dy = y - startY
        moved = moved || dx * dx + dy * dy > slop * slop
        if (directionDecided || max(abs(dx), abs(dy)) <= slop) return false
        directionDecided = true
        if (!target.allowsDrag || dy <= slop || dy <= abs(dx)) return false
        active = false
        return true
    }

    fun end(x: Float, y: Float, target: Target): Boolean {
        val dx = x - startX
        val dy = y - startY
        val dismiss = active && !moved && dx * dx + dy * dy <= slop * slop && this.target.allowsTap && target.allowsTap
        cancel()
        return dismiss
    }

    fun cancel() {
        active = false
    }

    companion object {
        fun imeBottomExtra(visible: Boolean, imeBottom: Int, systemBottom: Int): Int =
            if (visible) (imeBottom.coerceAtLeast(0) - systemBottom.coerceAtLeast(0)).coerceAtLeast(0) else 0

        fun supportsDone(singleLine: Boolean, action: Int): Boolean =
            singleLine && (action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_DONE)
    }
}
