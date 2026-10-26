package com.ditarector.diligent.sync.manifest

import com.ditarector.diligent.sync.port.FileStore

/**
 * Decides which paths never sync (SPEC A.7):
 *
 * 1. the built-in list: directories named `.git`, `node_modules`, `.venv`,
 *    `venv`, `__pycache__`, `dist`, `build`, `.next`, `.gradle` or `target`
 *    at any depth (`.git` also as a file, as in submodules), and the root
 *    paths `.diligent/logs/`, `.idea/workspace.xml` and `.idea/shelf/`;
 * 2. anything named `.env*`, unless [allowEnv] (the per-project setting; the
 *    sync additionally requires the peer to allow it);
 * 3. patterns from every `.gitignore` and `.diligentignore` in the project,
 *    with git's precedence: deeper files win, later lines win, and within
 *    one directory `.diligentignore` comes after `.gitignore`.
 *
 * The built-in list cannot be re-included with `!`, so a stray pattern can
 * never make `node_modules` sync. [ProjectPaths.PROJECT_FILE] and its folder
 * are exempt from ignore files, because the project's identity must sync.
 *
 * Ignore files are read lazily from [store] and cached; build a new instance
 * for each manifest so edited ignore files take effect.
 */
public class IgnoreRules(private val store: FileStore, public val allowEnv: Boolean = false) {
    private val patternsByDir = HashMap<String, List<GitignorePattern>>()

    /** True if [path] must not sync. Directories make everything below them ignored. */
    public fun isIgnored(path: String, isDirectory: Boolean): Boolean {
        if (!ProjectPaths.isValid(path)) return true
        val parts = path.split('/')
        // The identity file and the folder holding it are exempt from ignore files.
        val exempt = path == ".diligent" || path == ProjectPaths.PROJECT_FILE
        var prefix = ""
        for (i in parts.indices) {
            prefix = if (i == 0) parts[0] else prefix + "/" + parts[i]
            val dir = i < parts.size - 1 || isDirectory
            if (isBuiltIn(prefix, parts[i], dir)) return true
            if (!allowEnv && isEnvName(parts[i])) return true
            if (!exempt && matchesIgnoreFiles(prefix, dir)) return true
        }
        return false
    }

    private fun matchesIgnoreFiles(path: String, isDirectory: Boolean): Boolean {
        var result = false
        val parent = ProjectPaths.parent(path)
        val dirs = listOf("") + if (parent.isEmpty()) emptyList() else parent.split('/').runningReduce { a, b -> "$a/$b" }
        for (dir in dirs) {
            for (pattern in patternsIn(dir)) {
                if (pattern.matches(path, isDirectory)) result = !pattern.negated
            }
        }
        return result
    }

    private fun patternsIn(dir: String): List<GitignorePattern> = patternsByDir.getOrPut(dir) {
        IGNORE_FILES.flatMap { file ->
            val text = store.readText(if (dir.isEmpty()) file else "$dir/$file") ?: ""
            text.lineSequence().mapNotNull { GitignorePattern.parse(dir, it) }.toList()
        }
    }

    public companion object {
        /** Directory names never synced at any depth (A.7). */
        public val IGNORED_DIRECTORY_NAMES: Set<String> =
            setOf(".git", "node_modules", ".venv", "venv", "__pycache__", "dist", "build", ".next", ".gradle", "target")

        /** Root-relative directories never synced (A.7). */
        public val IGNORED_ROOT_DIRECTORIES: Set<String> = setOf(".diligent/logs", ".idea/shelf")

        /** Root-relative files never synced (A.7). */
        public val IGNORED_ROOT_FILES: Set<String> = setOf(".idea/workspace.xml")

        /** Ignore files read in every directory, in precedence order. */
        public val IGNORE_FILES: List<String> = listOf(".gitignore", ".diligentignore")

        /** True for a `.env*` name, which syncs only when both devices allow it. */
        public fun isEnvName(name: String): Boolean = name.startsWith(".env")

        /** True if any segment of [path] is a `.env*` name. */
        public fun isEnvPath(path: String): Boolean = path.split('/').any(::isEnvName)

        private fun isBuiltIn(path: String, name: String, isDirectory: Boolean): Boolean = when {
            name == ".git" -> true
            isDirectory && name in IGNORED_DIRECTORY_NAMES -> true
            isDirectory && path in IGNORED_ROOT_DIRECTORIES -> true
            !isDirectory && path in IGNORED_ROOT_FILES -> true
            else -> false
        }
    }
}
