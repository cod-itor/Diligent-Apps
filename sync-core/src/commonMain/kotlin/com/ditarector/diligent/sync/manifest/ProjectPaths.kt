package com.ditarector.diligent.sync.manifest

/**
 * Rules for the project-relative paths that travel in manifests, plans and
 * `file.*` messages. A peer is trusted with a project, not with the device,
 * so a path that could escape the project root is refused everywhere.
 */
public object ProjectPaths {
    /** Path of the project identity file, which always syncs (A.7). */
    public const val PROJECT_FILE: String = ".diligent/project.json"

    /**
     * True for a relative path with `/` separators and no empty, `.` or `..`
     * segment, no backslash, no NUL and no drive letter.
     */
    public fun isValid(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) return false
        if ('\\' in path || '\u0000' in path) return false
        if (path.length >= 2 && path[1] == ':') return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** The file name: everything after the last `/`. */
    public fun name(path: String): String = path.substringAfterLast('/')

    /** The parent directory, or "" for a file at the root. */
    public fun parent(path: String): String = if ('/' in path) path.substringBeforeLast('/') else ""
}
