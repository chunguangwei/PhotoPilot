package dev.photopilot.scaffold.ui.navigation

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.MainSection

internal class RailNavView(context: Context) : LinearLayout(context), NavChrome {

    override var onSectionClick: (MainSection) -> Unit = {}

    private val items: List<NavItemView>

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        val space8 = resources.getDimensionPixelSize(R.dimen.pp_space_8)
        items = MainSection.entries.map { section ->
            NavItemView(context, section, NavItemView.Style.RAIL).apply {
                onClick = { onSectionClick(it) }
                column.addView(
                    this,
                    LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        topMargin = space8
                        bottomMargin = space8
                    },
                )
            }
        }
        addView(column, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(
            separatorView(context),
            LayoutParams(SEPARATOR_SIZE_PX, LayoutParams.MATCH_PARENT),
        )
    }

    override fun selectSection(section: MainSection) {
        items.forEach { it.isSelected = it.section == section }
    }
}
