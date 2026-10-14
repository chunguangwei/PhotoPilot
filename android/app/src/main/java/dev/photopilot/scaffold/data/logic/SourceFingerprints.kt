package dev.photopilot.scaffold.data.logic

import dev.photopilot.scaffold.data.db.SourceEntity

object SourceFingerprints {

    fun of(source: SourceEntity): String =
        source.endpointKey ?: "${source.type.wire}:${source.id}"
}
