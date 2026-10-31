package com.syntra.os

import com.syntra.os.agent.Dot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DotsTest {
    @Test fun mentionMatchesSlugOrPrefix() {
        val dots = Dot.PRESETS
        assertEquals("scout" to "find phones", Dot.mention("@scout find phones", dots)!!.let { it.first.id to it.second })
        assertEquals("writer", Dot.mention("@wri draft", dots)!!.first.id)
        assertNull(Dot.mention("@zz hi", dots)); assertNull(Dot.mention("hi @scout", dots))
    }

    @Test fun permissionsGateTools() {
        val scout = Dot.PRESETS.first { it.id == "scout" }
        assertTrue(scout.allows("web_search")); assertTrue(scout.allows("review_page"))
        assertFalse(scout.allows("shell")); assertFalse(scout.allows("phone_tap"))
        assertFalse(scout.copy(memoryAllowed = false).allows("memory_remember"))
        assertTrue(Dot.PRESETS.first { it.id == "coder" }.allows("shell"))
    }
}
