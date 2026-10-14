package dev.photopilot.scaffold.ui

import dev.photopilot.scaffold.R

enum class MainSection(val titleRes: Int, val iconRes: Int) {
    FRAMES(R.string.nav_frames, R.drawable.ic_nav_frames_24),
    LIBRARY(R.string.nav_library, R.drawable.ic_nav_library_24),
    SOURCES(R.string.nav_sources, R.drawable.ic_nav_sources_24),
    SETTINGS(R.string.nav_settings, R.drawable.ic_nav_settings_24),
}
