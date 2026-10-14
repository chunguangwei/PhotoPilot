package dev.photopilot.scaffold

import java.io.File
import org.json.JSONObject

object Vectors {

    fun load(name: String): JSONObject {
        val candidates = listOf(
            File("../../contracts/vectors/$name"),
            File("../../../contracts/vectors/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("vector file not found: $name; tried $candidates from ${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    fun fixtureRoot(): File {
        val candidates = listOf(
            File("../../tests/fixtures/smoke-v1"),
            File("../../../tests/fixtures/smoke-v1"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("fixture root not found; tried $candidates")
    }

    fun fixture(rel: String): File {
        val file = File(fixtureRoot(), rel)
        require(file.isFile) { "fixture not found: $rel" }
        return file
    }
}
