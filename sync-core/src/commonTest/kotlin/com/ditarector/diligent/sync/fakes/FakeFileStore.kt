package com.ditarector.diligent.sync.fakes

import com.ditarector.diligent.sync.port.FileStat
import com.ditarector.diligent.sync.port.FileStore
import com.ditarector.diligent.sync.port.PathFilter

/** An in-memory project. [log] records writes so tests can check their order. */
class FakeFileStore(private val clock: () -> Long = { 1_000 }) : FileStore {
    class Entry(val bytes: ByteArray, val mtimeMs: Long, val executable: Boolean)

    val files = mutableMapOf<String, Entry>()
    val temps = mutableMapOf<String, ByteArray>()
    val log = mutableListOf<String>()
    var hashCalls = 0
    val visitedDirectories = mutableListOf<String>()

    fun put(path: String, text: String, mtimeMs: Long = clock(), executable: Boolean = false) = apply {
        files[path] = Entry(text.encodeToByteArray(), mtimeMs, executable)
    }

    fun text(path: String): String? = files[path]?.bytes?.decodeToString()

    override fun list(filter: PathFilter): List<FileStat> {
        val dirDecisions = mutableMapOf<String, Boolean>()
        fun dirIncluded(dir: String): Boolean = dirDecisions.getOrPut(dir) {
            val parent = dir.substringBeforeLast('/', "")
            (parent.isEmpty() || dirIncluded(parent)) && filter.include(dir, true).also { visitedDirectories += dir }
        }
        return files.keys.sorted().filter { path ->
            val parent = path.substringBeforeLast('/', "")
            (parent.isEmpty() || dirIncluded(parent)) && filter.include(path, false)
        }.map { FileStat(it, files.getValue(it).bytes.size.toLong(), files.getValue(it).mtimeMs, files.getValue(it).executable) }
    }

    override fun readText(path: String): String? = text(path)

    override fun sha256(path: String): String {
        hashCalls++
        return Sha256.hex(files.getValue(path).bytes)
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray {
        val bytes = files[path]?.bytes ?: throw NoSuchElementException(path)
        val start = offset.toInt().coerceAtMost(bytes.size)
        return bytes.copyOfRange(start, (start + length).coerceAtMost(bytes.size))
    }

    override fun exists(path: String): Boolean = path in files || files.keys.any { it.startsWith("$path/") }

    override fun writeTemp(path: String, offset: Long, bytes: ByteArray) {
        val existing = if (offset == 0L) ByteArray(0) else temps.getValue(path)
        check(existing.size.toLong() == offset) { "non-contiguous write to temp of $path" }
        temps[path] = existing + bytes
    }

    override fun tempSha256(path: String): String = Sha256.hex(temps.getValue(path))

    override fun commitTemp(path: String, target: String, executable: Boolean) {
        files[target] = Entry(temps.remove(path) ?: error("no temp for $path"), clock(), executable)
        log += "commit $target"
    }

    override fun discardTemp(path: String) {
        temps.remove(path)
    }

    override fun delete(path: String) {
        files.remove(path)
        log += "delete $path"
    }
}
