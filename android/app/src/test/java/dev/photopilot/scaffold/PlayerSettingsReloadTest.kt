package dev.photopilot.scaffold

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.ui.player.PlayerEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSettingsReloadTest {
    @Test
    fun changingPausedKeepAwakeTakesEffectWithoutResumingPlayback() {
        val frame = FrameEntity("frame", "Frame", null, "{}", 0, FrameOrderMode.CAPTURED_ASC, 0, 0, 0)
        val listener = RecordingListener()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"))
        }
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val environment = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafeField.get(null), AppEnvironment::class.java) as AppEnvironment
        val engine = PlayerEngine(context, environment, frame, listener, CoroutineScope(Dispatchers.Unconfined))
        try {
            val state = PlayerEngine::class.java.getDeclaredField("state").apply { isAccessible = true }
            state.set(engine, "paused")
            engine.reloadSettings(frame.copy(settingsJson = "{\"keepAwakeWhenPaused\":false}"))
            assertEquals(false, listener.keepAwake)
            assertEquals("paused", engine.currentState())
            engine.reloadSettings(frame.copy(settingsJson = "{\"keepAwakeWhenPaused\":true}"))
            assertEquals(true, listener.keepAwake)
            assertEquals("paused", engine.currentState())
        } finally {
            engine.stop(clearSnapshot = false)
        }
    }

    private class RecordingListener : PlayerEngine.Listener {
        var keepAwake: Boolean? = null
        override fun onKeepScreenOn(keep: Boolean) { keepAwake = keep }
        override fun onStateChanged(state: String) = Unit
        override fun onShowAsset(asset: AssetEntity, decoded: PlayerEngine.DecodedAsset, transitionMs: Long, direction: Int, position: Int) = Unit
        override fun onShowVideo(asset: AssetEntity, path: String, blurred: Bitmap?, edgeColor: Int?) = Unit
        override fun onClearTextures() = Unit
        override fun onShowSafeImage() = Unit
        override fun onQueueInfo(size: Int) = Unit
        override fun onDecodeSkipped() = Unit
    }
}
