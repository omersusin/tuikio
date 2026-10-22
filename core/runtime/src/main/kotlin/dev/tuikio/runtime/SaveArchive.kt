package dev.tuikio.runtime

import dev.tuikio.catalog.SafePath
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exports and imports a game's private home directory (its saves) as a zip. Imports are
 * treated as hostile input: entry names must be safe relative paths, sizes and counts are
 * capped, compression ratios are bounded, and nothing is written until the whole archive
 * has been validated into a staging directory that then replaces the old saves atomically.
 */
object SaveArchive {
    class Limits(
        val maxEntries: Int = 2000,
        val maxTotalBytes: Long = 64L * 1024 * 1024,
        val maxEntryBytes: Long = 16L * 1024 * 1024,
        val maxRatio: Int = 200,
    )

    class RejectedException(message: String) : IOException(message)

    private const val MARKER = ".tuikio-save"

    /** Writes every regular file under [home] (except temporary files) into [out]. */
    fun export(home: File, gameId: String, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write("game=$gameId\nformat=1\n".toByteArray())
            zip.closeEntry()
            home.walkTopDown()
                .onEnter { it.name != ".tmp" }
                .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .forEach { f ->
                    val rel = f.relativeTo(home).invariantSeparatorsPath
                    if (!SafePath.isRelative(rel)) return@forEach
                    zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /**
     * Restores saves for [gameId] into [home]. The archive must have been exported for the same
     * game. On any problem nothing changes and [RejectedException] explains why.
     */
    fun import(input: InputStream, gameId: String, home: File, limits: Limits = Limits()) {
        val parent = home.absoluteFile.parentFile ?: throw IOException("no parent for $home")
        val staging = File(parent, ".import-${System.nanoTime()}")
        staging.mkdirs()
        try {
            var entries = 0
            var total = 0L
            var sawMarker = false
            val counted = CountingInputStream(input)
            ZipInputStream(counted).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val compressedStart = counted.count
                    if (++entries > limits.maxEntries) throw RejectedException("too many files in the archive")
                    val name = e.name
                    if (e.isDirectory) continue
                    if (name == MARKER) {
                        val meta = zip.readBytes().toString(Charsets.UTF_8)
                        if (!meta.lines().contains("game=$gameId")) throw RejectedException("these saves belong to a different game")
                        sawMarker = true
                        continue
                    }
                    if (!SafePath.isRelative(name)) throw RejectedException("unsafe path in archive: $name")
                    val dst = File(staging, name)
                    if (!dst.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw RejectedException("unsafe path in archive: $name")
                    dst.parentFile.mkdirs()
                    var written = 0L
                    dst.outputStream().use { o ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > limits.maxEntryBytes) throw RejectedException("a file in the archive is too large")
                            if (total > limits.maxTotalBytes) throw RejectedException("the archive expands to too much data")
                            // Streamed entries don't declare sizes up front, so measure compressed
                            // bytes actually consumed. Tiny files are exempt from the ratio rule.
                            val consumed = (counted.count - compressedStart).coerceAtLeast(1)
                            if (written > 1024 * 1024 && written / consumed > limits.maxRatio) {
                                throw RejectedException("suspicious compression ratio (possible zip bomb)")
                            }
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            if (!sawMarker) throw RejectedException("not a Tuikio save archive")
            // Swap in: old saves move aside, new ones take their place, then the old ones go.
            val old = File(parent, ".old-${System.nanoTime()}")
            if (home.exists() && !home.renameTo(old)) throw IOException("could not move current saves aside")
            if (!staging.renameTo(home)) {
                old.renameTo(home)
                throw IOException("could not install imported saves")
            }
            old.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private class CountingInputStream(private val inner: InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** Bytes used by a directory tree (saves usage shown in Library). */
    fun sizeOf(dir: File): Long = if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
