package dev.photopilot.scaffold.data.logic

data class RuleUiState(val checked: Boolean, val recursive: Boolean, val dynamic: Boolean)

object FrameEditDiff {

    data class Result(
        val addIncludes: List<AddInclude>,
        val removeRefs: List<String>,
        val ruleChanges: List<RuleChange>,
        val removeRules: List<String>,
    )

    fun plan(
        includedAssetIds: Set<String>,
        dynamicRules: Map<String, SaveRuleRow>,
        selectedAssetIds: Set<String>,
        removedExcludes: Set<String>,
        ruleStates: Map<String, RuleUiState>,
        staticMembers: Map<String, List<String>>,
        now: Long,
    ): Result {
        val addIncludes = (selectedAssetIds - includedAssetIds)
            .sorted()
            .map { AddInclude(ref = it, addedAt = now) }
        val removeRefs = ((includedAssetIds - selectedAssetIds) + removedExcludes).sorted()
        val ruleChanges = mutableListOf<RuleChange>()
        val removeRules = mutableListOf<String>()
        for ((collectionId, ui) in ruleStates.toSortedMap()) {
            val existing = dynamicRules[collectionId]
            if (ui.checked) {
                if (ui.dynamic) {
                    if (existing == null || existing.recursive != ui.recursive) {
                        ruleChanges += RuleChange(
                            collectionId = collectionId,
                            recursive = ui.recursive,
                            includeFuture = true,
                            confirmed = true,
                            members = emptyList(),
                            addedAt = now,
                        )
                    }
                } else {
                    ruleChanges += RuleChange(
                        collectionId = collectionId,
                        recursive = ui.recursive,
                        includeFuture = false,
                        confirmed = false,
                        members = staticMembers[collectionId].orEmpty(),
                        addedAt = now,
                    )
                    if (existing != null) {
                        removeRules += collectionId
                    }
                }
            } else if (existing != null) {
                removeRules += collectionId
            }
        }
        return Result(
            addIncludes = addIncludes,
            removeRefs = removeRefs,
            ruleChanges = ruleChanges,
            removeRules = removeRules,
        )
    }
}
