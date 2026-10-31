package com.syntra.os

import com.syntra.os.ext.ExtensionManager
import com.syntra.os.ext.Importer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionsTest {
    @Test fun frontmatter() {
        val fm = Importer.frontmatter("---\nname: ponytail\ndescription: \"Lazy dev\"\n---\nbody")
        assertEquals("ponytail", fm["name"]); assertEquals("Lazy dev", fm["description"])
        assertTrue(Importer.frontmatter("no header").isEmpty())
    }

    @Test fun dedupesSkillCopies() = runBlocking {
        val tree = listOf("skills/a/SKILL.md", ".cursor/skills/a/SKILL.md", "skills/a/ref.md", "install.sh").map { Importer.Entry(it, 10) }
        val (m, files) = Importer.generate("o/r", tree) { "---\nname: a\ndescription: d\n---" }
        assertEquals(1, m.skills.size); assertEquals("skills/a/SKILL.md", m.skills[0].file)
        assertTrue("install.sh" !in files)
    }

    @Test fun shellSafeRejectsInjection() {
        assertEquals("'scrapling'", Importer.shellSafe("scrapling"))
        assertThrows(IllegalArgumentException::class.java) { Importer.shellSafe("x; rm -rf /") }
        assertThrows(IllegalArgumentException::class.java) { Importer.shellSafe("\$(id)") }
    }

    @Test fun parseRepo() {
        val m = ExtensionManager(File("build/tmp-ext"), { "" })
        assertEquals("a/b" to null, m.parse("https://github.com/a/b.git"))
        assertEquals("a/b" to "main", m.parse("github.com/a/b/tree/main"))
        assertThrows(IllegalArgumentException::class.java) { m.parse("a/b;c") }
    }
}
