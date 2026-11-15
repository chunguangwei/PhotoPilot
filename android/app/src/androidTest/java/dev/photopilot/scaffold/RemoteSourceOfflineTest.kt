package dev.photopilot.scaffold

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteSourceOfflineTest {
    @Test fun metadataOnlySourceRefreshOfflineKeepsAppAliveWithoutImportJob() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(500)
        val id = "remote-offline-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val port = ServerSocket(0).use { it.localPort }
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", port))
        env.credentialStore.save(id, JsonMini.toJson(mapOf("protocol" to "http", "host" to "10.0.2.15", "port" to port, "path" to "", "username" to "", "password" to "")))
        env.database.sourceDao().upsert(SourceEntity(id, SourceType.WEBDAV, "Offline fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true,\"directories\":[\"\"]}", now, now))
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("nasSyncNow", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, id, false)
            }
            delay(1200)
            instrumentation.runOnMainSync { assertFalse(activity.isFinishing) }
            assertTrue(env.database.importJobDao().getBySource(id).isEmpty())
            assertEquals(SourceStatus.ACTIVE, env.database.sourceDao().getById(id)?.status)
            assertNotNull(env.database.settingsKvDao().get("nas.connection_error.$id"))
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(root?.findAccessibilityNodeInfosByText(context.getString(R.string.error_src_unavailable_body)).orEmpty().isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.credentialStore.delete(id)
            env.database.collectionDao().getBySource(id).sortedByDescending { it.relPath?.length ?: 0 }.forEach { env.database.collectionDao().delete(it) }
            env.database.sourceDao().getById(id)?.let { env.database.sourceDao().delete(it) }
        }
        Unit
    }
}
