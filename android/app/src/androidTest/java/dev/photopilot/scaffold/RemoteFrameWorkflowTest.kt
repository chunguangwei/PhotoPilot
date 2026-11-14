package dev.photopilot.scaffold

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.remote.*
import dev.photopilot.scaffold.data.repo.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteFrameWorkflowTest {
    @Test fun metadataSelectionAndTemporaryPlaybackKeepOriginalsEmpty() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val id = "remote-test-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        var opens = 0
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val share = object : dev.photopilot.scaffold.data.net.SmbShare {
            override fun security() = dev.photopilot.scaffold.data.net.SmbClient.Security("SMB3", true, true)
            override fun list(path: String) = if (path.isEmpty()) listOf(dev.photopilot.scaffold.data.net.SmbEntry("family", true)) else listOf(dev.photopilot.scaffold.data.net.SmbEntry("test.png", false, bytes.size.toLong(), 1))
            override fun openRead(path: String) = ByteArrayInputStream(bytes).also { opens++ }
        }
        val adapter = SmbSourceAdapter(share, id, recursive = true)
        db.sourceDao().upsert(SourceEntity(id, SourceType.SMB, "Fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        val media = RemoteMediaRepository(context, db, { adapter }, { true })
        val manager = FrameManager(db)
        val frame = manager.createFrame("Remote fixture")
        try {
            media.index(id, "{\"directories\":[\"\"]}", adapter)
            assertEquals(0, opens)
            val sourceAsset = db.sourceAssetDao().getBySource(id).single()
            val repo = FrameRepository(db, LibraryVersionRepository(db.settingsKvDao()), File(context.filesDir, "originals")).also { it.remoteMedia = media }
            db.frameDao().update(frame.copy(settingsJson = "{\"mediaStorageMode\":\"remote\"}"))
            repo.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, sourceAsset.id)))
            assertEquals(listOf("remote:${sourceAsset.id}"), repo.buildPlayQueue(frame.id))
            val item = repo.playbackItems(frame.id).single()
            assertEquals(32, media.read(item) { file, _ -> android.graphics.BitmapFactory.decodeFile(file.path).width })
            assertEquals(1, opens)
            assertTrue(db.assetOriginDao().getBySourceAssets(listOf(sourceAsset.id)).isEmpty())
            assertTrue(db.assetDao().getByIds(listOf(sourceAsset.id)).isEmpty())
            for (extension in LocalFilesSourceAdapter.MEDIA_EXTENSIONS) {
                val compatible = sourceAsset.copy(id = "$id-$extension", remoteKey = "formats/photo.$extension")
                db.sourceAssetDao().upsert(compatible)
                assertNotNull("Existing static extension remains eligible: $extension", media.item(compatible.id))
            }
            for (extension in listOf("mp4", "mov")) {
                val video = sourceAsset.copy(id = "$id-$extension", remoteKey = "video.$extension")
                db.sourceAssetDao().upsert(video)
                assertNull(media.item(video.id))
            }
            val source = requireNotNull(db.sourceDao().getById(id))
            db.sourceDao().update(source.copy(epoch = 2, status = SourceStatus.DISCONNECTED))
            assertFalse(media.valid(item))
            assertTrue(repo.buildPlayQueue(frame.id).isEmpty())
        } finally {
            media.cache.clear()
            manager.deleteFrame(frame.id)
            db.collectionDao().getBySource(id).sortedByDescending { it.relPath?.length ?: 0 }.forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            db.close()
        }
        Unit
    }
}
