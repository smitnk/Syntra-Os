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
