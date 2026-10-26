package com.ditarector.diligent.sync.manifest

import com.ditarector.diligent.sync.fakes.FakeFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreRulesTest {
    private val store = FakeFileStore()
    private fun rules(allowEnv: Boolean = false) = IgnoreRules(store, allowEnv)

    @Test
    fun builtInDirectoriesAreIgnoredAtAnyDepth() {
        val r = rules()
        for (name in listOf(".git", "node_modules", ".venv", "venv", "__pycache__", "dist", "build", ".next", ".gradle", "target")) {
            assertTrue(r.isIgnored(name, true), name)
            assertTrue(r.isIgnored("$name/x/y.txt", false), "$name/x/y.txt")
            assertTrue(r.isIgnored("packages/web/$name/z.js", false), "nested $name")
        }
    }

    @Test
    fun builtInNamesAsFilesAreNotIgnoredExceptGit() {
        val r = rules()
        assertFalse(r.isIgnored("build", false), "a script called build syncs")
        assertFalse(r.isIgnored("src/dist", false))
        assertFalse(r.isIgnored("src/build.gradle.kts", false))
        assertFalse(r.isIgnored("buildSrc/a.kt", false))
        assertTrue(r.isIgnored("vendor/lib/.git", false), "submodule gitlink file")
    }

    @Test
    fun builtInRootPaths() {
        val r = rules()
        assertTrue(r.isIgnored(".diligent/logs", true))
        assertTrue(r.isIgnored(".diligent/logs/run-1.log", false))
        assertFalse(r.isIgnored(".diligent/run.json", false))
        assertFalse(r.isIgnored(".diligent/project.json", false))
        assertTrue(r.isIgnored(".idea/workspace.xml", false))
        assertTrue(r.isIgnored(".idea/shelf/change.patch", false))
        assertFalse(r.isIgnored(".idea/misc.xml", false), "rest of .idea syncs")
        assertFalse(r.isIgnored(".vscode/settings.json", false), "editor settings sync")
    }

    @Test
    fun envFilesOnlyWhenAllowed() {
        for (path in listOf(".env", ".env.local", ".envrc", "api/.env.production")) {
            assertTrue(rules().isIgnored(path, false), path)
            assertFalse(rules(allowEnv = true).isIgnored(path, false), path)
        }
        assertFalse(rules().isIgnored("src/env.ts", false))
        assertFalse(rules().isIgnored("config/my.env", false))
        assertTrue(IgnoreRules.isEnvPath("a/.env"))
        assertFalse(IgnoreRules.isEnvPath("a/env"))
    }

    @Test
    fun gitignorePatterns() {
        store.put(
            ".gitignore",
            """
            # comment
            *.log
            /secret.txt
            out/
            docs/**/draft.md
            temp?.txt
            [ab]cache
            \#hash
            coverage/**
            """.trimIndent(),
        )
        val r = rules()
        assertTrue(r.isIgnored("app.log", false))
        assertTrue(r.isIgnored("deep/er/app.log", false), "no slash: any depth")
        assertTrue(r.isIgnored("secret.txt", false))
        assertFalse(r.isIgnored("sub/secret.txt", false), "leading slash anchors to the root")
        assertTrue(r.isIgnored("out", true))
        assertTrue(r.isIgnored("src/out/gen.js", false), "dir pattern at any depth")
        assertFalse(r.isIgnored("src/out", false), "trailing slash: directories only")
        assertTrue(r.isIgnored("docs/draft.md", false), "** matches zero directories")
        assertTrue(r.isIgnored("docs/a/b/draft.md", false))
        assertFalse(r.isIgnored("other/docs/draft.md", false), "middle slash anchors")
        assertTrue(r.isIgnored("temp1.txt", false))
        assertFalse(r.isIgnored("temp12.txt", false))
        assertTrue(r.isIgnored("acache", false))
        assertFalse(r.isIgnored("ccache", false))
        assertTrue(r.isIgnored("#hash", false))
        assertTrue(r.isIgnored("coverage/x/lcov.info", false))
        assertFalse(r.isIgnored("comment", false))
    }

    @Test
    fun negationAndPrecedence() {
        store.put(".gitignore", "*.log\n!keep.log\n")
        store.put("logs/.gitignore", "!debug.log\n")
        store.put("logs/.diligentignore", "debug.log\n")
        store.put("api/.gitignore", "!*.log\n")
        val r = rules()
        assertTrue(r.isIgnored("x.log", false))
        assertFalse(r.isIgnored("keep.log", false), "later line wins")
        assertTrue(r.isIgnored("logs/debug.log", false), ".diligentignore comes after .gitignore in the same folder")
        assertFalse(r.isIgnored("api/server.log", false), "deeper ignore file wins")
    }

    @Test
    fun ignoredDirectoryCannotBeReincludedFromInside() {
        store.put(".gitignore", "vendor/\n!vendor/keep.txt\n")
        assertTrue(rules().isIgnored("vendor/keep.txt", false))
    }

    @Test
    fun builtInsCannotBeReincluded() {
        store.put(".gitignore", "!node_modules/\n!.env\n")
        assertTrue(rules().isIgnored("node_modules/a.js", false))
        assertTrue(rules().isIgnored(".env", false))
    }

    @Test
    fun projectIdentityAlwaysSyncs() {
        store.put(".gitignore", ".diligent/\n")
        val r = rules()
        assertFalse(r.isIgnored(".diligent", true))
        assertFalse(r.isIgnored(ProjectPaths.PROJECT_FILE, false))
        assertTrue(r.isIgnored(".diligent/run.json", false), "the rest of .diligent follows .gitignore")
    }

    @Test
    fun invalidPathsAreIgnored() {
        val r = rules()
        assertTrue(r.isIgnored("../escape", false))
        assertTrue(r.isIgnored("/abs", false))
        assertTrue(r.isIgnored("a//b", false))
        assertTrue(r.isIgnored("a\\b", false))
        assertTrue(r.isIgnored("C:/x", false))
    }

    @Test
    fun manifestBuilderSkipsIgnoredTreesWithoutWalkingThem() {
        store.put(".gitignore", "*.tmp\n")
        store.put("src/app.js", "console.log(1)", mtimeMs = 5, executable = false)
        store.put("run.sh", "#!/bin/sh", mtimeMs = 6, executable = true)
        store.put("node_modules/lib/index.js", "x")
        store.put("a.tmp", "x")
        store.put(".env", "KEY=1")
        val manifest = ManifestBuilder.build(store, rules())
        assertEquals(listOf(".gitignore", "run.sh", "src/app.js"), manifest.entries.map { it.path })
        assertTrue(manifest["run.sh"]!!.executable)
        assertEquals(14, manifest["src/app.js"]!!.size)
        assertEquals(5, manifest["src/app.js"]!!.mtimeMs)
        assertFalse("node_modules/lib" in store.visitedDirectories, "ignored directory not entered")
        assertTrue(".env" in ManifestBuilder.build(store, rules(allowEnv = true)))
    }

    @Test
    fun manifestBuilderReusesHashesOfUnchangedFiles() {
        store.put("a.txt", "one", mtimeMs = 1)
        store.put("b.txt", "two", mtimeMs = 1)
        val first = ManifestBuilder.build(store, rules())
        assertEquals(2, store.hashCalls)
        store.put("b.txt", "TWO", mtimeMs = 2)
        val second = ManifestBuilder.build(store, rules(), previous = first)
        assertEquals(3, store.hashCalls, "only b.txt rehashed")
        assertEquals(first["a.txt"], second["a.txt"])
        assertEquals(Manifest.fromJson(second.toJson()), second)
    }
}
