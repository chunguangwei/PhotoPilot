package dev.photopilot.scaffold.data.logic

data class SaveIncludeRow(val ref: String, val manualOrder: Long?, val addedAt: Long)

data class SaveRuleRow(val collectionId: String, val recursive: Boolean, val includeFuture: Boolean)

data class AddInclude(val ref: String, val addedAt: Long)

data class RuleChange(
    val collectionId: String,
    val recursive: Boolean,
    val includeFuture: Boolean,
    val confirmed: Boolean,
    val members: List<String>,
    val addedAt: Long,
)

data class SelectionSaveRequest(
    val addIncludes: List<AddInclude> = emptyList(),
    val removeRefs: List<String> = emptyList(),
    val addExcludes: List<String> = emptyList(),
    val ruleChanges: List<RuleChange> = emptyList(),
    val removeRules: List<String> = emptyList(),
)

data class SelectionSaveInput(
    val currentVersion: Long,
    val baseVersion: Long,
    val orderMode: String,
    val existingIncludes: List<SaveIncludeRow>,
    val existingExcludes: List<String>,
    val existingRules: List<SaveRuleRow>,
    val request: SelectionSaveRequest,
)

sealed interface SelectionSaveResult {
    data class Ok(
        val newVersion: Long,
        val includeRows: List<SaveIncludeRow>,
        val excludeRows: List<String>,
        val ruleRows: List<SaveRuleRow>,
    ) : SelectionSaveResult

    data class Conflict(val error: String) : SelectionSaveResult

    data object Rejected : SelectionSaveResult
}

object SelectionSavePlanner {

    const val ERROR_CONFLICT = "SELECTION_CONFLICT"

    private data class MutableRow(
        val mode: String,
        val manualOrder: Long?,
        val addedAt: Long,
    )

    fun plan(input: SelectionSaveInput): SelectionSaveResult {
        if (input.baseVersion != input.currentVersion) {
            return SelectionSaveResult.Conflict(ERROR_CONFLICT)
        }
        if (input.request.ruleChanges.any { it.includeFuture && !it.confirmed }) {
            return SelectionSaveResult.Rejected
        }

        val rows = mutableMapOf<String, MutableRow>()
        for (row in input.existingIncludes) {
            rows[row.ref] = MutableRow(SelectionResolver.MODE_INCLUDE, row.manualOrder, row.addedAt)
        }
        for (ref in input.existingExcludes) {
            rows[ref] = MutableRow(SelectionResolver.MODE_EXCLUDE, null, 0L)
        }
        val rules = input.existingRules.associateBy { it.collectionId }.toMutableMap()

        for (ref in input.request.removeRefs) {
            rows.remove(ref)
        }
        for (add in input.request.addIncludes) {
            val existing = rows[add.ref]
            rows[add.ref] = MutableRow(
                SelectionResolver.MODE_INCLUDE,
                existing?.manualOrder,
                existing?.addedAt ?: add.addedAt,
            )
        }
        for (ref in input.request.addExcludes) {
            val existing = rows[ref]
            rows[ref] = MutableRow(
                SelectionResolver.MODE_EXCLUDE,
                null,
                existing?.addedAt ?: 0L,
            )
        }
        for (change in input.request.ruleChanges) {
            if (change.includeFuture) {
                rules[change.collectionId] = SaveRuleRow(change.collectionId, change.recursive, true)
            } else {
                for (member in change.members) {
                    val existing = rows[member]
                    if (existing?.mode == SelectionResolver.MODE_EXCLUDE) continue
                    rows[member] = MutableRow(
                        SelectionResolver.MODE_INCLUDE,
                        existing?.manualOrder,
                        existing?.addedAt ?: change.addedAt,
                    )
                }
            }
        }
        for (collectionId in input.request.removeRules) {
            rules.remove(collectionId)
        }

        var includeRows = rows
            .filterValues { it.mode == SelectionResolver.MODE_INCLUDE }
            .map { SaveIncludeRow(it.key, it.value.manualOrder, it.value.addedAt) }
        if (input.orderMode == PlaybackQueue.ORDER_MANUAL) {
            val renumbered = includeRows.sortedWith(
                compareBy<SaveIncludeRow> { it.manualOrder == null }
                    .thenBy { it.manualOrder ?: 0L }
                    .thenBy { it.addedAt }
                    .thenBy { it.ref },
            )
            includeRows = renumbered.mapIndexed { index, row ->
                row.copy(manualOrder = (index + 1).toLong())
            }
        }

        return SelectionSaveResult.Ok(
            newVersion = input.currentVersion + 1,
            includeRows = includeRows.sortedBy { it.ref },
            excludeRows = rows
                .filterValues { it.mode == SelectionResolver.MODE_EXCLUDE }
                .keys
                .sorted(),
            ruleRows = rules.values.sortedBy { it.collectionId },
        )
    }
}
