package com.ditarector.diligent.sync.conflict

import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictNamesTest {
    private fun name(path: String, device: String = "Laptop") = ConflictNames.copyName(path, device)

    @Test
    fun simpleExtension() {
        assertEquals("notes (from Laptop).txt", name("notes.txt"))
    }

    @Test
    fun keepsTheFolder() {
        assertEquals("src/ui/App (from Laptop).tsx", name("src/ui/App.tsx"))
    }

    @Test
    fun noExtension() {
        assertEquals("Makefile (from Laptop)", name("Makefile"))
        assertEquals("bin/run (from Laptop)", name("bin/run"))
    }

    @Test
    fun dotfiles() {
        assertEquals(".bashrc (from Laptop)", name(".bashrc"))
        assertEquals("config/.prettierrc (from Laptop)", name("config/.prettierrc"))
        assertEquals("..hidden (from Laptop)", name("..hidden"))
    }

    @Test
    fun dotfileWithExtension() {
        assertEquals(".env (from Laptop).local", name(".env.local"))
        assertEquals(".eslintrc (from Laptop).json", name(".eslintrc.json"))
    }

    @Test
    fun multipleDotsUseTheLastOne() {
        assertEquals("archive.tar (from Laptop).gz", name("archive.tar.gz"))
        assertEquals("app.test (from Laptop).js", name("app.test.js"))
        assertEquals("v1.2.3 (from Laptop).md", name("v1.2.3.md"))
    }

    @Test
    fun trailingDot() {
        assertEquals("odd. (from Laptop)", name("odd."))
    }

    @Test
    fun folderWithDotDoesNotLendAnExtension() {
        assertEquals("my.folder/README (from Laptop)", name("my.folder/README"))
    }

    @Test
    fun deviceNameIsCleaned() {
        assertEquals("a (from Toto's Phone).txt", name("a.txt", "Toto's Phone"))
        assertEquals("a (from a_b_c).txt", name("a.txt", "a/b\\c"))
        assertEquals("a (from other device).txt", name("a.txt", "  "))
        assertEquals("a (from x_y).txt", name("a.txt", "x\ny"))
    }

    @Test
    fun uniqueNameSkipsExistingCopies() {
        val existing = setOf("a (from Laptop).txt", "a (from Laptop 2).txt")
        assertEquals("a (from Laptop 3).txt", ConflictNames.uniqueCopyName("a.txt", "Laptop") { it in existing })
        assertEquals("b (from Laptop).txt", ConflictNames.uniqueCopyName("b.txt", "Laptop") { it in existing })
        assertEquals("Makefile (from Laptop 2)", ConflictNames.uniqueCopyName("Makefile", "Laptop") { it == "Makefile (from Laptop)" })
    }
}
