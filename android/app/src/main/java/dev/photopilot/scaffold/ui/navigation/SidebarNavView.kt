package dev.photopilot.scaffold.ui.navigation

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import dev.photopilot.scaffold.ui.widget.BrandMarkView
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.MainSection

internal class SidebarNavView(context: Context) : LinearLayout(context), NavChrome {

    override var onSectionClick: (MainSection) -> Unit = {}

    private val items: List<NavItemView>

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        val res = resources
        val space4 = res.getDimensionPixelSize(R.dimen.pp_space_4)
        val space16 = res.getDimensionPixelSize(R.dimen.pp_space_16)
        val space24 = res.getDimensionPixelSize(R.dimen.pp_space_24)
        val space32 = res.getDimensionPixelSize(R.dimen.pp_space_32)

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        val header = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(space24, space32, space24, space24)
            val name = TextView(context).apply {
                text = res.getString(R.string.brand_name)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
                setTextColor(context.getColor(R.color.pp_text_primary))
            }
            val subtitle = TextView(context).apply {
                text = res.getString(R.string.nav_subtitle)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
            }
            val brand = FrameLayout(context).apply {
                name.setPadding(space24 + space4, 0, 0, 0)
                addView(name, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(BrandMarkView(context), FrameLayout.LayoutParams(space24, space24, Gravity.START or Gravity.CENTER_VERTICAL))
            }
            addView(brand, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                subtitle,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = space4
                },
            )
        }
        column.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val itemContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        items = MainSection.entries.map { section ->
            NavItemView(context, section, NavItemView.Style.SIDEBAR).apply {
                onClick = { onSectionClick(it) }
                itemContainer.addView(
                    this,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = space4
                        bottomMargin = space4
                    },
                )
            }
        }
        column.addView(itemContainer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = TextView(context).apply {
            text = res.getString(R.string.nav_privacy_footer)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            setPadding(space24, space16, space24, space24)
        }
        column.addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
