package dev.photopilot.scaffold.data.aiframes

object AINameSuggestions {
    val themes = linkedMapOf(
        "nature" to ("自然风景" to "Nature"), "beach" to ("海边" to "Seaside"),
        "pets" to ("宠物" to "Pets"), "food" to ("美食" to "Food"),
        "travel" to ("旅行" to "Travels"), "flowers" to ("花卉" to "Flowers"),
        "city" to ("城市建筑" to "City"), "sunset" to ("日落" to "Sunsets"),
    )
    fun names(configuration: AIConfiguration, chinese: Boolean): List<String> {
        val top = configuration.themes.maxByOrNull { it.value }?.key?.let(themes::get) ?: return emptyList()
        val theme = if (chinese) top.first else top.second
        val description = configuration.rule.description.take(36)
        return listOf(theme, "$description · $theme").distinct()
    }
}
