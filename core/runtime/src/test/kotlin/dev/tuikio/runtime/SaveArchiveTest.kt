package dev.tuikio.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveArchiveTest {
    private val root = Files.createTempDirectory("saves").toFile()

    private fun zip(vararg entries: Pair<String, ByteArray>, marker: String? = "game=vitetris\nformat=1\n"): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            if (marker != null) {
                z.putNextEntry(ZipEntry(".tuikio-save")); z.write(marker.toByteArray()); z.closeEntry()
            }
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun exportThenImportRoundTrips() {
        val home = File(root, "vitetris/home").apply { mkdirs() }
        File(home, ".vitetris").writeText("hiscore=4210")
        File(home, "sub/levels.dat").apply { parentFile.mkdirs(); writeText("x") }
        File(home, ".tmp").mkdirs()
        File(home, ".tmp/junk").writeText("temp")
        val bytes = ByteArrayOutputStream().also { SaveArchive.export(home, "vitetris", it) }.toByteArray()

        val restored = File(root, "other/home")
        SaveArchive.import(ByteArrayInputStream(bytes), "vitetris", restored)
        assertEquals("hiscore=4210", File(restored, ".vitetris").readText())
        assertEquals("x", File(restored, "sub/levels.dat").readText())
        assertFalse(File(restored, ".tmp/junk").exists(), "temporary files are not exported")
    }

    @Test
    fun rejectsTraversalAbsolutePathsAndForeignArchives() {
        val home = File(root, "g/home").apply { mkdirs() }
        File(home, "keep").writeText("original")
        listOf("../evil", "/etc/passwd", "a/../../b", "a\\b").forEach { name ->
            assertFailsWith<SaveArchive.RejectedException>(name) {
                SaveArchive.import(ByteArrayInputStream(zip(name to "x".toByteArray())), "vitetris", home)
            }
        }
        assertFailsWith<SaveArchive.RejectedException> {
            SaveArchive.import(ByteArrayInputStream(zip("ok" to "x".toByteArray(), marker = null)), "vitetris", home)
        }
        assertFailsWith<SaveArchive.RejectedException> {
            SaveArchive.import(ByteArrayInputStream(zip("ok" to "x".toByteArray(), marker = "game=greed\n")), "vitetris", home)
        }
        assertEquals("original", File(home, "keep").readText(), "failed imports leave saves untouched")
        assertTrue(root.walkTopDown().none { it.name.startsWith(".import-") }, "staging is cleaned up")
    }

    @Test
    fun rejectsZipBombs() {
        val home = File(root, "bomb/home").apply { mkdirs() }
        val zeros = ByteArray(8 * 1024 * 1024)
        assertFailsWith<SaveArchive.RejectedException> {
            SaveArchive.import(ByteArrayInputStream(zip("big" to zeros)), "vitetris", home, SaveArchive.Limits(maxRatio = 100))
        }
        assertFailsWith<SaveArchive.RejectedException> {
            SaveArchive.import(ByteArrayInputStream(zip("a" to ByteArray(10), "b" to ByteArray(10))), "vitetris", home, SaveArchive.Limits(maxEntries = 2))
        }
        // An oversized marker must not be read into memory whole.
        assertFailsWith<SaveArchive.RejectedException> {
            SaveArchive.import(ByteArrayInputStream(zip("ok" to "x".toByteArray(), marker = "game=vitetris\n" + " ".repeat(50_000_000))), "vitetris", home)
        }
    }
}
