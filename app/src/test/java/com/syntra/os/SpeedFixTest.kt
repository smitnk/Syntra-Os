package com.syntra.os

import com.syntra.os.device.TermIntents
import com.syntra.os.local.LfmPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedFixTest {
    @Test fun terminalIntents() {
        assertEquals("apt-get update && apt-get install -y ffmpeg imagemagick", TermIntents.match("install ffmpeg and imagemagick"))
        assertTrue(TermIntents.match("install hermes agent")!!.contains("hermes-agent.nousresearch.com"))
        assertTrue(TermIntents.match("run hermes agent summarise my notes")!!.endsWith("hermes -q 'summarise my notes' -Q"))
        assertTrue(TermIntents.match("run hermes agent")!!.contains("hermes -q 'Say hello"))
        assertEquals("pip install requests", TermIntents.match("pip install requests"))
        assertNull(TermIntents.match("install x; rm -rf /"))
        assertNull(TermIntents.match("write a python script that renames my photos"))
    }

    @Test fun lfm2PythonicToolCalls() {
        val r = LfmPrompt.parse("<|tool_call_start|>[web_search(query=\"news, today\"), open_app(name='YouTube', n=2)]<|tool_call_end|>")
        assertEquals(listOf("web_search", "open_app"), r.toolCalls.map { it.name })
        assertEquals("{\"query\":\"news, today\"}", r.toolCalls[0].argsJson)
        assertEquals("{\"name\":\"YouTube\",\"n\":2}", r.toolCalls[1].argsJson)
    }
}
