package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.AISourceScope
import org.junit.Assert.*
import org.junit.Test

class AISourceScopeTest {
    @Test fun recursiveFoldersDeduplicateAndTerminateCycles() {
        val parents = mapOf("a" to "c", "b" to "a", "c" to "b", "other" to null)
        assertEquals(setOf("a", "b", "c"), AISourceScope.expand(listOf("a", "b", "missing"), true, parents))
        assertEquals(setOf("a"), AISourceScope.expand(listOf("a", "a", "missing"), false, parents))
    }
    @Test fun deepFolderTreesUseBoundedTraversal() {
        val parents = (0 until 5000).associate { it.toString() to if (it == 0) null else (it - 1).toString() }
        assertEquals(5000, AISourceScope.expand(listOf("0"), true, parents).size)
    }
}
