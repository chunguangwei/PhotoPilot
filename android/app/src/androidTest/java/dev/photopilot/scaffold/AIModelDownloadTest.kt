package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIModelDownloadTest {
    @Test fun completePartialRecoversFromHashWithoutAnotherNetworkRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AIModelManager(context)
        val compact = manager.models.single { it.runtime == "onnx" }
        val file = compact.files.single { it.name == "vocab.txt" }.copy(international = emptyList(), domestic = emptyList())
        val model = compact.copy(id = "complete-part-" + UUID.randomUUID(), files = listOf(file))
        val folder = manager.directory(model)
        try {
            File(context.filesDir, "ai-validation/multilingual-clip/vocab.txt").copyTo(File(folder, file.name + ".part"))
            manager.download(model, "auto", true) { _, _ -> }
            assertTrue(manager.ready(model))
            assertTrue(manager.verify(model))
            assertFalse(File(folder, file.name + ".part").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun verifiedFileRecoversMissingMarkerAndBrokenResumeMetadataDownloadsCorrectBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AIModelManager(context)
        val compact = manager.models.single { it.id == "multilingual-clip-vitb32-lite" }
        val tokenizer = compact.files.single { it.name == "vocab.txt" }.let { it.copy(domestic = it.domestic.take(1)) }
        val model = compact.copy(id = "download-validation-" + UUID.randomUUID(), files = listOf(tokenizer))
        val folder = manager.directory(model)
        val destination = File(folder, tokenizer.name)
        val fixture = File(context.filesDir, "ai-validation/multilingual-clip/vocab.txt")
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
            manager.download(model, "domestic", allowMetered = true) { count, total -> assertTrue(count <= total) }
            assertTrue(manager.verify(model))
            assertTrue(manager.ready(model))
            assertFalse(File(folder, tokenizer.name + ".part").exists())
            assertFalse(File(folder, tokenizer.name + ".resume").exists())
            destination.delete()
            File(folder, tokenizer.name + ".verified").delete()
            File(folder, tokenizer.name + ".part").writeBytes(ByteArray(4096))
            File(folder, tokenizer.name + ".resume").writeText(org.json.JSONObject().put("url", tokenizer.domestic.single()).put("sha256", tokenizer.sha256).put("etag", "").toString())
            manager.download(model, "domestic", allowMetered = true) { _, _ -> }
            assertTrue(manager.verify(model))
            assertTrue(manager.ready(model))
        } finally { folder.deleteRecursively() }
    }
}
