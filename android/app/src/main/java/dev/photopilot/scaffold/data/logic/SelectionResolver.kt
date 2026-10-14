package dev.photopilot.scaffold.data.logic

data class SelectionRef(val assetId: String?, val sourceAssetId: String?, val mode: String)

data class DirectoryRuleRef(val collectionId: String, val recursive: Boolean)

data class ResolvableAsset(
    val id: String,
    val readiness: String,
    val privacyExcluded: Boolean,
    val deleted: Boolean,
    val fileExists: Boolean,
    val policyAllowed: Boolean,
)

data class SelectionResolveInput(
    val selections: List<SelectionRef>,
    val rules: List<DirectoryRuleRef>,
    val collectionChildren: Map<String, List<String>>,
    val collectionAssets: Map<String, List<String>>,
    val assets: Map<String, ResolvableAsset>,
)

data class SelectionResolution(
    val candidates: List<String>,
    val pending: List<String>,
    val allowed: List<String>,
    val playable: List<String>,
)

object SelectionResolver {

    const val MODE_INCLUDE = "include"
    const val MODE_EXCLUDE = "exclude"
    const val READINESS_READY = "ready"

    fun resolve(input: SelectionResolveInput): SelectionResolution {
        val includedAssets = sortedSetOf<String>()
        val pendingSources = sortedSetOf<String>()
        val excludedRefs = sortedSetOf<String>()
        for (selection in input.selections) {
            val ref = FrameSelectionLogic.refKey(selection.assetId, selection.sourceAssetId)
            when (selection.mode) {
                MODE_INCLUDE -> {
                    if (selection.assetId != null) includedAssets.add(selection.assetId)
                    if (selection.sourceAssetId != null) pendingSources.add(selection.sourceAssetId)
                }
                MODE_EXCLUDE -> excludedRefs.add(ref)
            }
        }

        val directoryAssets = sortedSetOf<String>()
        for (rule in input.rules) {
            for (collectionId in expand(rule, input.collectionChildren)) {
                directoryAssets.addAll(input.collectionAssets[collectionId].orEmpty())
            }
        }

        val candidates = (includedAssets + directoryAssets).toSortedSet().toList()
        val pending = pendingSources.toList()

        val allowed = buildList {
            for (id in candidates) {
                val asset = input.assets[id] ?: continue
                if (id in excludedRefs) continue
                if (asset.privacyExcluded || asset.deleted) continue
                add(id)
            }
            for (id in pending) {
                if (id !in excludedRefs) add(id)
            }
        }

        val playable = allowed.filter { id ->
            val asset = input.assets[id]
            asset != null &&
                asset.readiness == READINESS_READY &&
                asset.fileExists &&
                asset.policyAllowed
        }

        return SelectionResolution(
            candidates = candidates,
            pending = pending,
            allowed = allowed,
            playable = playable,
        )
    }

    private fun expand(rule: DirectoryRuleRef, children: Map<String, List<String>>): Set<String> {
        val visited = linkedSetOf(rule.collectionId)
        if (!rule.recursive) return visited
        val queue = ArrayDeque<String>()
        queue.add(rule.collectionId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in children[current].orEmpty()) {
                if (visited.add(child)) queue.add(child)
            }
        }
        return visited
    }
}
