package com.ditarector.diligent.sync.manifest

/**
 * One line of a `.gitignore` or `.diligentignore`, with git's semantics:
 * `#` comments, `!` negation, a trailing `/` for directories only, a `/`
 * at the start or in the middle anchoring the pattern to the file's
 * directory, `*`, `?`, `[...]`, `**` and backslash escapes.
 *
 * @property base the directory holding the ignore file, "" for the root.
 */
internal class GitignorePattern private constructor(
    val base: String,
    val negated: Boolean,
    private val directoryOnly: Boolean,
    private val anchored: Boolean,
    private val segments: List<String>,
) {
    /** True if [path] (project-relative) matches. Only paths below [base] can. */
    fun matches(path: String, isDirectory: Boolean): Boolean {
        if (directoryOnly && !isDirectory) return false
        val relative = when {
            base.isEmpty() -> path
            path.startsWith("$base/") -> path.substring(base.length + 1)
            else -> return false
        }
        val parts = relative.split('/')
        return if (anchored) matchSegments(0, parts, 0) else globMatch(segments[0], 0, parts.last(), 0)
    }

    private fun matchSegments(pi: Int, parts: List<String>, si: Int): Boolean {
        if (pi == segments.size) return si == parts.size
        val segment = segments[pi]
        if (segment == "**") {
            // A trailing `**` matches everything inside, but not the directory itself.
            val min = if (pi == segments.size - 1) 1 else 0
            for (skip in min..(parts.size - si)) if (matchSegments(pi + 1, parts, si + skip)) return true
            return false
        }
        if (si == parts.size) return false
        return globMatch(segment, 0, parts[si], 0) && matchSegments(pi + 1, parts, si + 1)
    }

    companion object {
        /** Parses [line] of the ignore file in [base]; null for blanks and comments. */
        fun parse(base: String, line: String): GitignorePattern? {
            var text = trimTrailingSpaces(line.removeSuffix("\r"))
            if (text.isEmpty() || text.startsWith("#")) return null
            var negated = false
            if (text.startsWith("!")) {
                negated = true
                text = text.substring(1)
            }
            val directoryOnly = text.endsWith("/")
            text = text.trimEnd('/')
            if (text.isEmpty()) return null
            val anchored = '/' in text
            text = text.removePrefix("/")
            if (text.isEmpty()) return null
            val segments = text.split('/').filter { it.isNotEmpty() }
            return GitignorePattern(base, negated, directoryOnly, anchored, segments)
        }

        private fun trimTrailingSpaces(text: String): String {
            var end = text.length
            while (end > 0 && text[end - 1] == ' ' && !(end >= 2 && text[end - 2] == '\\')) end--
            return text.substring(0, end)
        }

        /** Matches one path segment against one glob segment. */
        internal fun globMatch(pattern: String, pi: Int, name: String, ni: Int): Boolean {
            var p = pi
            var n = ni
            while (p < pattern.length) {
                when (val c = pattern[p]) {
                    '*' -> {
                        while (p < pattern.length && pattern[p] == '*') p++
                        if (p == pattern.length) return true
                        for (k in n..name.length) if (globMatch(pattern, p, name, k)) return true
                        return false
                    }
                    '?' -> {
                        if (n >= name.length) return false
                        p++
                        n++
                    }
                    '[' -> {
                        if (n >= name.length) return false
                        val end = classEnd(pattern, p)
                        if (end < 0) {
                            if (name[n] != '[') return false
                            p++
                            n++
                        } else {
                            if (!classMatches(pattern.substring(p + 1, end), name[n])) return false
                            p = end + 1
                            n++
                        }
                    }
                    '\\' -> {
                        val literal = if (p + 1 < pattern.length) pattern[p + 1] else '\\'
                        if (n >= name.length || name[n] != literal) return false
                        p += 2
                        n++
                    }
                    else -> {
                        if (n >= name.length || name[n] != c) return false
                        p++
                        n++
                    }
                }
            }
            return n == name.length
        }

        private fun classEnd(pattern: String, open: Int): Int {
            var i = open + 1
            if (i < pattern.length && (pattern[i] == '!' || pattern[i] == '^')) i++
            if (i < pattern.length && pattern[i] == ']') i++
            while (i < pattern.length) {
                if (pattern[i] == ']') return i
                i++
            }
            return -1
        }

        private fun classMatches(body: String, c: Char): Boolean {
            var i = 0
            var negate = false
            if (body.isNotEmpty() && (body[0] == '!' || body[0] == '^')) {
                negate = true
                i = 1
            }
            var matched = false
            while (i < body.length) {
                val start = body[i]
                if (i + 2 < body.length && body[i + 1] == '-') {
                    if (c in start..body[i + 2]) matched = true
                    i += 3
                } else {
                    if (c == start) matched = true
                    i++
                }
            }
            return matched != negate
        }
    }
}
