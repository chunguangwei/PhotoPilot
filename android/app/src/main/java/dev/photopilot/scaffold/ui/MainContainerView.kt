package dev.photopilot.scaffold.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.navigation.BottomNavView
import dev.photopilot.scaffold.ui.navigation.NavChrome
import dev.photopilot.scaffold.ui.navigation.RailNavView
import dev.photopilot.scaffold.ui.navigation.SidebarNavView

class MainContainerView(context: Context) : FrameLayout(context) {

    private enum class ChromeTier { BOTTOM, RAIL, SIDEBAR }

    private val contentContainer = FrameLayout(context)
    private var chromeView: View? = null
    private var chromeTier: ChromeTier? = null

    var currentSection: MainSection = MainSection.FRAMES
        private set

    var sectionContent: ((MainSection) -> View?)? = null

    init {
        addView(
            contentContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (contentContainer.childCount == 0) {
            showSection(currentSection)
        }
    }

    fun selectSection(section: MainSection) {
        if (section == currentSection && contentContainer.childCount > 0) {
            (chromeView as? NavChrome)?.selectSection(section)
            return
        }
        currentSection = section
        showSection(section)
        (chromeView as? NavChrome)?.selectSection(section)
    }

    private fun showSection(section: MainSection) {
        contentContainer.removeAllViews()
        val content = sectionContent?.invoke(section)
            ?: SectionPlaceholderView(context, section.titleRes)
        contentContainer.addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthPx = MeasureSpec.getSize(widthMeasureSpec)
        if (widthPx > 0) {
            val widthDp = widthPx / resources.displayMetrics.density
            val tier = when {
                widthDp < COMPACT_MAX_WIDTH_DP -> ChromeTier.BOTTOM
                widthDp < SIDEBAR_MIN_WIDTH_DP -> ChromeTier.RAIL
                else -> ChromeTier.SIDEBAR
            }
            if (tier != chromeTier) {
                chromeTier = tier
                rebuildChrome(tier)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (chromeTier == ChromeTier.BOTTOM) {
            val chrome = chromeView ?: return
            val contentLp = contentContainer.layoutParams as LayoutParams
            if (contentLp.bottomMargin != chrome.height) {
                contentLp.bottomMargin = chrome.height
                contentContainer.layoutParams = contentLp
            }
        }
    }

    private fun rebuildChrome(tier: ChromeTier) {
        chromeView?.let { removeView(it) }
        val chrome: View
        val chromeLp: LayoutParams
        val contentLp = contentContainer.layoutParams as LayoutParams
        contentLp.bottomMargin = 0
        when (tier) {
            ChromeTier.BOTTOM -> {
                chrome = BottomNavView(context)
                chromeLp = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                }
                contentLp.marginStart = 0
                contentLp.marginEnd = 0
            }
            ChromeTier.RAIL -> {
                chrome = RailNavView(context)
                chromeLp = LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.pp_rail_width),
                    LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.START
                }
                contentLp.marginStart = chromeLp.width
                contentLp.marginEnd = 0
            }
            ChromeTier.SIDEBAR -> {
                chrome = SidebarNavView(context)
                chromeLp = LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.pp_sidebar_width),
                    LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = Gravity.START
                }
                contentLp.marginStart = chromeLp.width
                contentLp.marginEnd = 0
            }
        }
        contentContainer.layoutParams = contentLp
        (chrome as NavChrome).apply {
            onSectionClick = { section -> this@MainContainerView.selectSection(section) }
            selectSection(currentSection)
        }
        addView(chrome, chromeLp)
        chromeView = chrome
        if (isLaidOut) {
            requestLayout()
        }
    }

    private companion object {
        const val COMPACT_MAX_WIDTH_DP = 600f
        const val SIDEBAR_MIN_WIDTH_DP = 840f
    }
}
