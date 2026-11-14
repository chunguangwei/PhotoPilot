package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIModelDownloadTest {
    @Test fun verifiedFileRecoversMissingMarkerAndBrokenResumeMetadataDownloadsCorrectBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AIModelManager(context)
        val compact = manager.models.single { it.id == "siglip2-base-int8" }
        val tokenizer = compact.files.single { it.name == "tokenizer.model" }
        val model = compact.copy(id = "download-validation-" + UUID.randomUUID(), files = listOf(tokenizer))
        val folder = manager.directory(model)
        val destination = File(folder, tokenizer.name)
        val fixture = File(context.filesDir, "ai-validation/tokenizer.model")
        try {
            fixture.copyTo(destination)
            assertFalse(manager.ready(model))
            manager.download(model, "international", allowMetered = true) { _, _ -> }
            assertTrue(manager.ready(model))
            assertTrue(manager.verify(model))
            java.io.RandomAccessFile(destination, "rw").use { file ->
                val first = file.readByte()
                file.seek(0)
                file.writeByte(first.toInt() xor 1)
            }
            assertFalse(manager.verify(model))
            assertFalse(manager.ready(model))
            destination.delete()
            File(folder, tokenizer.name + ".verified").delete()
            File(folder, tokenizer.name + ".part").writeText("broken partial bytes")
            File(folder, tokenizer.name + ".resume").writeText("{broken resume metadata")
            manager.download(model, "international", allowMetered = true) { count, total -> assertTrue(count <= total) }
            assertTrue(manager.verify(model))
            assertTrue(manager.ready(model))
            assertFalse(File(folder, tokenizer.name + ".part").exists())
            assertFalse(File(folder, tokenizer.name + ".resume").exists())
        } finally { folder.deleteRecursively() }
    }
}
