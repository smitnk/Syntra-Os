package com.syntra.os

import com.syntra.os.core.ChatClient
import com.syntra.os.memory.Graph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphTest {
    @Test fun upsertMergesSameLabel() {
        val (g1, a) = Graph().upsert("Sumit", "person", "animator")
        val (g2, b) = g1.upsert("sumit", "concept", "developer")
        assertEquals(1, g2.nodes.size)
        assertEquals(a.id, b.id)
        assertEquals("person", b.type)
        assertTrue("developer" in b.notes && "animator" in b.notes)
    }

    @Test fun communitiesSplitDisconnectedClusters() {
        var g = Graph()
        listOf("a", "b", "c", "x", "y", "z").forEach { g = g.upsert(it, "concept", "").first }
        fun id(l: String) = g.find(l)!!.id
        g = g.link(id("a"), id("b"), "r").link(id("b"), id("c"), "r").link(id("a"), id("c"), "r")
        g = g.link(id("x"), id("y"), "r").link(id("y"), id("z"), "r").link(id("x"), id("z"), "r")
        val c = g.communities()
        assertEquals(c[id("a")], c[id("c")])
        assertEquals(c[id("x")], c[id("z")])
        assertNotEquals(c[id("a")], c[id("x")])
    }

    @Test fun removeDropsEdges() {
        var g = Graph().upsert("a", "c", "").first.upsert("b", "c", "").first
        g = g.link(g.find("a")!!.id, g.find("b")!!.id, "knows")
        assertEquals(0, g.remove(g.find("a")!!.id).edges.size)
    }

    @Test fun parsesToolCalls() {
        val body = """{"choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"c1","type":"function","function":{"name":"memory_search","arguments":"{\"query\":\"x\"}"}}]}}]}"""
        val r = ChatClient.parse(body)
        assertEquals("memory_search", r.toolCalls.single().name)
        assertEquals("", r.content)
    }
}

class SkillsTest {
    @Test fun parsesFrontmatterAndStartsDisabled() {
        val s = com.syntra.os.agent.Skills.parse("---\nname: Growth Hacker\ndescription: \"Finds growth\"\n---\n# Body\nDo things", "https://x/y.md")
        assertEquals("Growth Hacker", s.name)
        assertEquals("Finds growth", s.description)
        assertEquals("# Body\nDo things", s.prompt)
        assertTrue(!s.enabled)
    }

    @Test fun convertsBlobUrl() {
        assertEquals("https://raw.githubusercontent.com/a/b/main/c.md",
            com.syntra.os.agent.Skills.rawUrl("https://github.com/a/b/blob/main/c.md"))
    }
}

class LocalTest {
    @Test fun parsesLocalToolCalls() {
        val r = com.syntra.os.local.LfmPrompt.parse(
            "Let me check.\n<tool>{\"name\": \"phone_screen\", \"arguments\": {}}</tool>\n<tool>{\"name\":\"ask_specialist\",\"arguments\":{\"role\":\"VISION\",\"question\":\"q\"}}</tool>")
        assertEquals(listOf("phone_screen", "ask_specialist"), r.toolCalls.map { it.name })
        assertEquals("Let me check.", r.content)
    }

    @Test fun plainAnswerHasNoCalls() {
        assertEquals(0, com.syntra.os.local.LfmPrompt.parse("Hello Sumit").toolCalls.size)
    }

    @Test fun rendersLfm3() {
        val p = com.syntra.os.local.LfmPrompt.render(listOf(com.syntra.os.core.message("system", "S"),
            com.syntra.os.core.message("user", "hi")), null, "<__media__>")
        assertEquals("<|im_start|>system\nS<|im_end|>\n<|im_start|>user\n<__media__>\nhi<|im_end|>\n<|im_start|>assistant\n<think></think>", p.text)
        assertEquals("<|im_start|>system\nS<|im_end|>\n".length, p.stableBytes)
    }

    @Test fun parsesNativeLfm3Call() {
        val r = com.syntra.os.local.LfmPrompt.parse("<tool_call>\n<function=open_app>\n<parameter=name>\nYouTube\n</parameter>\n</function>\n</tool_call>")
        assertEquals("open_app", r.toolCalls.single().name)
        assertEquals("""{"name":"YouTube"}""", r.toolCalls.single().argsJson)
        assertEquals("", r.content)
    }

    @Test fun parsesTagNameThenJson() { // exact output from the v0.3 screenshot
        val r = com.syntra.os.local.LfmPrompt.parse("<tool>open_app</tool>\n{\n  \"name\": \"YouTube\"\n  \"arguments\": {}\n}")
        assertEquals("open_app", r.toolCalls.single().name)
        assertEquals("""{"name":"YouTube"}""", r.toolCalls.single().argsJson)
    }

    @Test fun stripsThinking() {
        assertEquals("Hi", com.syntra.os.local.LfmPrompt.parse("<think>hmm</think>Hi").content)
    }

    @Test fun picksPhoneQuantAndProjector() {
        val (m, p) = com.syntra.os.local.pickFiles(listOf("README.md", "LFM3-VL-2B-F16.gguf", "LFM3-VL-2B-Q4_0.gguf",
            "LFM3-VL-2B-Q8_0.gguf", "mmproj-LFM3-VL-2B-F16.gguf", "mmproj-LFM3-VL-2B-Q8_0.gguf"))!!
        assertEquals("LFM3-VL-2B-Q4_0.gguf", m)
        assertEquals("mmproj-LFM3-VL-2B-Q8_0.gguf", p)
    }

    @Test fun wakeWord() {
        val w = listOf("syntra", "jarvis")
        assertEquals("open youtube", com.syntra.os.voice.afterWakeWord("Hey Syntra, open youtube", w))
        assertEquals("", com.syntra.os.voice.afterWakeWord("Jarvis", w))
        assertEquals(null, com.syntra.os.voice.afterWakeWord("what time is it", w))
    }
}

class OsTest {
    @Test fun slashPicksSkill() {
        val skills = listOf(com.syntra.os.core.Skill("UI Critic", "d", "p"), com.syntra.os.core.Skill("Researcher", "d", "p"))
        val (s, rest) = com.syntra.os.agent.AgenticOs.parseSlash("/ui-critic check my app", skills)!!
        assertEquals("UI Critic", s!!.name)
        assertEquals("check my app", rest)
        assertEquals(null, com.syntra.os.agent.AgenticOs.parseSlash("/code make a game", skills)!!.first)
        assertEquals(null, com.syntra.os.agent.AgenticOs.parseSlash("hello", skills))
    }

    @Test fun approvalRules() {
        val empty = kotlinx.serialization.json.JsonObject(emptyMap())
        assertTrue(com.syntra.os.agent.isSensitive("shell", empty, askPhone = true, askLinux = true))
        assertTrue(!com.syntra.os.agent.isSensitive("shell", empty, askPhone = true, askLinux = false))
        assertTrue(com.syntra.os.agent.isSensitive("mcp__zapier__send_email", empty, askPhone = false, askLinux = false))
        assertTrue(!com.syntra.os.agent.isSensitive("web_search", empty, askPhone = true, askLinux = true))
    }

    @Test fun compactToolKeepsNamesAndRequired() {
        val t = com.syntra.os.agent.TOOLS.first { it.toString().contains("\"web_search\"") }
        val c = com.syntra.os.local.LfmPrompt.compactTool(t)
        assertEquals("""{"name":"web_search","description":"Search the web (DuckDuckGo). Use for news, facts, anything current. Then web_fetch the best result.","parameters":{"query":"Search words"},"required":["query"]}""", c)
    }

    @Test fun contentPartsAreText() {
        val m = com.syntra.os.core.json.parseToJsonElement("""{"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""")
        assertEquals("ab", com.syntra.os.local.LfmPrompt.textOf(m as kotlinx.serialization.json.JsonObject))
    }
}

class RouterTest {
    @Test fun routesByKeywords() {
        val r = com.syntra.os.agent.Router
        assertTrue(com.syntra.os.agent.Route.WEB in r.keywordRoutes("tell me today's war news"))
        assertTrue(com.syntra.os.agent.Route.PHONE in r.keywordRoutes("Open YouTube"))
        assertTrue(com.syntra.os.agent.Route.CODE in r.keywordRoutes("write a python script"))
        assertTrue(r.keywordRoutes("thanks!").isEmpty())
        val names = r.toolNames(setOf(com.syntra.os.agent.Route.WEB), code = false)
        assertTrue("web_search" in names && "memory_search" in names && "shell" !in names)
    }

    @Test fun anthropicToOpenAi() {
        val req = com.syntra.os.core.json.parseToJsonElement("""{"system":"S","messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"a.txt"}]}],
            "tools":[{"name":"Bash","description":"run","input_schema":{"type":"object"}}]}""") as kotlinx.serialization.json.JsonObject
        val (m, t) = com.syntra.os.local.LocalServer.toOpenAiStatic(req)
        assertEquals(listOf("system", "user", "assistant", "tool"), m.map { it["role"].toString().trim('"') })
        assertEquals("Bash", t!!.single().toString().let { Regex("\"name\":\"(\\w+)\"").find(it)!!.groupValues[1] })
    }
}

class GenericModelTest {
    @Test fun parsesQwenStyleToolCall() {
        val r = com.syntra.os.local.LfmPrompt.parse("<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"news\"}}\n</tool_call>")
        assertEquals("web_search", r.toolCalls.single().name)
        assertEquals("""{"query":"news"}""", r.toolCalls.single().argsJson)
        assertEquals("", r.content)
    }

    @Test fun flattensToolTurnsForAnyTemplate() {
        val m = listOf(com.syntra.os.core.message("system", "S"), com.syntra.os.core.message("user", "q"),
            com.syntra.os.core.toolResult("1", "a"), com.syntra.os.core.toolResult("2", "b"))
        val (roles, contents) = com.syntra.os.local.GenericPrompt.flatten(m, null, null)
        assertEquals(listOf("system", "user"), roles) // tool results merge into one user turn after the question
        assertTrue("<tool_response>\na\n</tool_response>" in contents[1] && "b" in contents[1])
    }
}
