package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.TofuPinStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TofuPinStoreTest {

    private fun fakeStore(): Pair<TofuPinStore, MutableMap<String, String>> {
        val backing = mutableMapOf<String, String>()
        return TofuPinStore(
            getValue = { backing[it] },
            putValue = { key, value -> backing[key] = value },
        ) to backing
    }

    @Test
    fun pinRoundtripAndDelete() = runBlocking {
        val (store, _) = fakeStore()
        assertNull(store.pinFor("nas.local:445"))
        store.savePin("nas.local:445", TofuPinStore.Pin("ab".repeat(32), 100L, 200L))
        val pin = store.pinFor("nas.local:445")
        assertEquals("ab".repeat(32), pin?.fingerprint)
        assertEquals(100L, pin?.firstSeenAt)
        assertEquals(200L, pin?.confirmedAt)
        store.deletePin("nas.local:445")
        assertNull(store.pinFor("nas.local:445"))
        Unit
    }

    @Test
    fun cleartextHostsRoundtrip() = runBlocking {
        val (store, _) = fakeStore()
        assertEquals(emptySet<String>(), store.cleartextHosts())
        store.addCleartextHost("10.0.0.9:80")
        store.addCleartextHost("nas.local:80")
        assertEquals(setOf("10.0.0.9:80", "nas.local:80"), store.cleartextHosts())
        Unit
    }

    @Test
    fun corruptedJsonYieldsEmpty() = runBlocking {
        val (store, backing) = fakeStore()
        backing[TofuPinStore.KEY_TOFU_PINS] = "{not-json"
        assertNull(store.pinFor("nas.local:445"))
        backing[TofuPinStore.KEY_CLEARTEXT_HOSTS] = "garbage"
        assertEquals(emptySet<String>(), store.cleartextHosts())
        Unit
    }
}
