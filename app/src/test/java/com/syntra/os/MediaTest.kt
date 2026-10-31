package com.syntra.os

import com.syntra.os.media.Documents
import com.syntra.os.media.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class MediaTest {
    @Test fun parsesMarkdownBlocks() {
        val b = Documents.parse("# Title\n\nIntro **bold** text\nwraps here.\n\n- one\n- two\n\n1. first")
        assertEquals(Documents.Heading(1, "Title"), b[0])
        assertEquals(Documents.Para("Intro **bold** text wraps here."), b[1])
        assertEquals(listOf(Documents.Bullet("one"), Documents.Bullet("two"), Documents.Bullet("first", 1)), b.drop(2))
        assertEquals(listOf("a" to true, " b" to false), Documents.runs("**a** b"))
    }

    @Test fun docxIsValidZipWithEscapedText() {
        val f = Files.createTempFile("t", ".docx").toFile()
        Documents.writeDocx("# Q&A\n\nPrice < 5 **now**", f)
        ZipFile(f).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).readBytes().decodeToString()
            assertTrue("Q&amp;A" in xml && "Price &lt; 5 " in xml && "<w:b/>" in xml)
            assertTrue(z.getEntry("[Content_Types].xml") != null)
        }
    }

    @Test fun ffmpegCommandRejectsInjection() {
        val c = Media.command(listOf("clips/a.mp4"), "out/b.mp4", "-ss 5 -t 10 -vf scale=720:-2")
        assertTrue("-i '/root/workspace/clips/a.mp4'" in c && "'/root/workspace/out/b.mp4'" in c)
        assertThrows(IllegalArgumentException::class.java) { Media.command(listOf("a.mp4"), "b.mp4", "-f null; rm -rf /") }
        assertThrows(IllegalArgumentException::class.java) { Media.command(listOf("../x.mp4"), "b.mp4", "") }
        assertThrows(IllegalArgumentException::class.java) { Media.command(listOf("a.mp4"), "b.mp4", "\$(id)") }
    }
}
