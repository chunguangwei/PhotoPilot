package dev.photopilot.scaffold.ui.navigation

import android.content.Context
import android.view.View
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.MainSection

internal const val SEPARATOR_SIZE_PX = 1

internal interface NavChrome {
    var onSectionClick: (MainSection) -> Unit
    fun selectSection(section: MainSection)
}

internal fun separatorView(context: Context): View = View(context).apply {
    setBackgroundColor(context.getColor(R.color.pp_separator))
}
