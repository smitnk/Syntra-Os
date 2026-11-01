package com.syntra.os

import com.syntra.os.agent.Cognition
import com.syntra.os.local.GenericPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitionTest {
    @Test fun decidesWhenToResearch() {
        assertTrue(Cognition.shouldResearch("Research today's AI news", true, 0, false))
        assertTrue(Cognition.shouldResearch("need specific details on straight of hermuze conflict", true, 0, false))
        assertFalse(Cognition.shouldResearch("today's AI news", true, 2, learnedFresh = true)) // learned it today already
        assertFalse(Cognition.shouldResearch("hi there", true, 0, false))
        assertFalse(Cognition.shouldResearch("open youtube", true, 0, false))
        assertFalse(Cognition.shouldResearch("what is the latest news", false, 0, false)) // Ask mode / no web
        assertTrue(Cognition.shouldResearch("who founded Liquid AI company", true, 0, false))
        assertFalse(Cognition.shouldResearch("who founded Liquid AI company", true, 1, false)) // already known
    }

    @Test fun catchesRepeats() {
        val a = "Here's today's war news summary based on your memory: Global Conflict Updates, ongoing military operations in the region with casualties and aid."
        assertTrue(Cognition.isRepeat(a, a))
        assertFalse(Cognition.isRepeat("The Strait of Hormuz carries about a fifth of world oil; Iran threatened closure after strikes, tankers rerouted.", a))
        assertTrue(Cognition.shrinkOldAnswer(a + "\n\nmore text " .repeat(20)).endsWith("(earlier answer, shortened)"))
    }

    @Test fun lfm2ToolList() {
        val tools = JsonArray(listOf(buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", "web_search"); put("description", "Search") } }))
        val b = GenericPrompt.lfm2ToolBlock(tools)
        assertTrue("<|tool_list_start|>[" in b && "\"web_search\"" in b && "<|tool_call_start|>" in b)
    }
}
