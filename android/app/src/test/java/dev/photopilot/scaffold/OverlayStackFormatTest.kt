package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.JsonMini
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayStackFormatTest {

    @Test
    fun stackRoundTripsThroughJsonMini() {
        val stack = listOf(
            mapOf("type" to "player", "frameId" to "f-1"),
            mapOf("type" to "progress", "jobId" to "j-2"),
            mapOf("type" to "frameEdit", "frameId" to ""),
        )
        val json = JsonMini.toJson(mapOf("stack" to stack))
        val parsed = JsonMini.parseObject(json)["stack"] as List<*>
        assertEquals(3, parsed.size)
        val entries = parsed.map { it as Map<*, *> }
        assertEquals("player", entries[0]["type"])
        assertEquals("j-2", entries[1]["jobId"])
        assertEquals("", entries[2]["frameId"])
    }

    @Test
    fun emptyAndMissingStackParseSafely() {
        assertEquals(null, JsonMini.parseObject("{}")["stack"])
        assertEquals(null, JsonMini.parseObject("not-json")["stack"])
    }
}
