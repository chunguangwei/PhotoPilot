package dev.photopilot.scaffold.ui.navigation

import android.content.Context
import android.widget.LinearLayout
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.MainSection

internal class BottomNavView(context: Context) : LinearLayout(context), NavChrome {

    override var onSectionClick: (MainSection) -> Unit = {}

    private val items: List<NavItemView>

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        addView(
            separatorView(context),
            LayoutParams(LayoutParams.MATCH_PARENT, SEPARATOR_SIZE_PX),
        )
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        items = MainSection.entries.map { section ->
            NavItemView(context, section, NavItemView.Style.BOTTOM).apply {
                onClick = { onSectionClick(it) }
                row.addView(this, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun selectSection(section: MainSection) {
        items.forEach { it.isSelected = it.section == section }
    }
}
