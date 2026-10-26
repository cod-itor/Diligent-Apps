package com.ditarector.diligent.sync.port

/**
 * One project's files on disk, rooted at the project folder. Every path is
 * relative to that root and uses `/` (see
 * [com.ditarector.diligent.sync.manifest.ProjectPaths]); the adapter maps it
 * to the platform's file system and must never resolve outside the root.
 *
 * Received files are written to a temporary file first (one per path, on
 * the same volume so the final rename is atomic), verified, then committed;
 * a reader never sees a half-written file (SPEC A.7 Safety).
 */
public interface FileStore {
    /**
     * Lists regular files. [filter] is asked about each directory before it is
     * entered and about each file before it is listed; returning false skips
     * it, so ignored trees such as `node_modules` are never walked. Symbolic
     * links are skipped: following them could leave the project.
     */
    public fun list(filter: PathFilter): List<FileStat>

    /** The UTF-8 text of [path], or null if it does not exist. Used for ignore files. */
    public fun readText(path: String): String?

    /** Lowercase hex SHA-256 of the file at [path]. */
    public fun sha256(path: String): String

    /** Up to [length] bytes of [path] from [offset]; fewer only at the end of the file. */
    public fun read(path: String, offset: Long, length: Int): ByteArray

    /** True if a file or directory exists at [path]. */
    public fun exists(path: String): Boolean

    /** Writes [bytes] at [offset] of the temporary file for [path]; offset 0 starts a new one. */
    public fun writeTemp(path: String, offset: Long, bytes: ByteArray)

    /** Lowercase hex SHA-256 of the temporary file for [path]. */
    public fun tempSha256(path: String): String

    /**
     * Renames the temporary file for [path] to [target] (usually [path]
     * itself; a conflict copy otherwise), creating parent directories and
     * replacing an existing file, and sets the executable bit.
     */
    public fun commitTemp(path: String, target: String, executable: Boolean)

    /** Deletes the temporary file for [path], if any. */
    public fun discardTemp(path: String)

    /** Deletes the file at [path]; directories left empty may be removed too. */
    public fun delete(path: String)
}

/** Decides which paths [FileStore.list] visits. */
public fun interface PathFilter {
    /** True to enter a directory or list a file. */
    public fun include(path: String, isDirectory: Boolean): Boolean
}

/** A file as [FileStore.list] reports it, before hashing. */
public data class FileStat(val path: String, val size: Long, val mtimeMs: Long, val executable: Boolean)
