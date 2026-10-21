package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.LocalUserDataCleaner
import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalUserDataCleanerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedPrivateFileRemovalAbortsCleanup() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        File(files, "music").mkdirs()
        val retained = File(files, "music/retained.m4a").apply { writeText("private audio") }

        val error = assertThrows(IOException::class.java) {
            LocalUserDataCleaner.clear(files, cache) { target ->
                if (target.name == "music") false else target.deleteRecursively()
            }
        }

        assertTrue(retained.isFile)
        assertFalse(error.message.orEmpty().contains(retained.absolutePath))
    }

    @Test fun falselyReportedSuccessWithRemainingPrivateFilesAbortsCleanup() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        File(files, "music").mkdirs()
        File(files, "music/retained.m4a").writeText("private audio")

        assertThrows(IOException::class.java) {
            LocalUserDataCleaner.clear(files, cache) { true }
        }
    }

    @Test fun eraseAllRemovesImportedMusicAndUnreferencedPartialCopies() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val source = temporary.newFile("source-audio.m4a").apply { writeText("source audio") }
        File(files, "music").mkdirs()
        source.copyTo(File(files, "music/selected.m4a"))
        File(files, "music/abandoned-import.part").writeText("partial copy")

        LocalUserDataCleaner.clear(files, cache)

        assertFalse(File(files, "music").exists())
        assertTrue(source.isFile)
    }

    @Test fun eraseAllRemovesPhotosCredentialsAndImportCachesWithNestedFiles() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val targets = listOf(
            File(files, "originals"),
            File(files, "credentials"),
            File(cache, "picked"),
            File(cache, "import-tmp"),
            File(cache, "diagnostics"),
        )
        targets.forEach { target ->
            File(target, "nested").mkdirs()
            File(target, "nested/content").writeText("synthetic data")
        }

        LocalUserDataCleaner.clear(files, cache)

        targets.forEach { assertFalse(it.exists()) }
        LocalUserDataCleaner.clear(files, cache)
    }
}
