package com.syntra.os

import com.syntra.os.rag.Chunker
import com.syntra.os.rag.Embedder
import com.syntra.os.rag.KnowledgeStore
import com.syntra.os.rag.PageText
import com.syntra.os.rag.relevantHit
import com.syntra.os.rag.rrf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class KnowledgeTest {
    private val filler = listOf("system", "device", "battery", "screen", "network", "setting", "update", "storage", "camera", "user",
        "service", "warranty", "button", "cable", "power", "mode", "display", "sensor", "signal", "memory")

    /** A 200-page "manual" of filler with 20 planted facts; the eval asks for each fact. */
    private fun manual(): Pair<List<PageText>, List<Triple<String, String, Int>>> {
        val r = Random(7)
        val facts = (1..20).map { i -> Triple("What is the torque for bolt model ZX$i?", "Bolt model ZX$i must be tightened to ${10 + i} newton metres.", i * 10 - 3) }
        val pages = (1..200).map { p ->
            val para = (1..6).joinToString("\n\n") { (1..30).joinToString(" ") { filler[r.nextInt(filler.size)] } + "." }
            val fact = facts.firstOrNull { it.third == p }?.second.orEmpty()
            PageText(p, "Section $p overview\n\n$para\n\n$fact")
        }
        return pages to facts
    }

    @Test fun chunksKeepPagesAndSize() {
        val cs = Chunker.split("d", manual().first)
        assertTrue(cs.all { it.page in 1..200 && it.text.length <= Chunker.SIZE + Chunker.OVERLAP + 200 })
        assertTrue(cs.any { "ZX7 " in it.text && it.page == 67 })
    }

    @Test fun bm25RecallOnPlantedFacts() {
        val st = KnowledgeStore(Files.createTempDirectory("kb").toFile())
        val (pages, facts) = manual()
        st.add("Manual.pdf", "test", pages, null)
        val t = System.currentTimeMillis()
        val hitAt1 = facts.count { (q, a, p) -> st.search(q, 5).firstOrNull()?.let { it.chunk.page == p && a in it.chunk.text } == true }
        val ms = (System.currentTimeMillis() - t) / facts.size
        assertTrue("recall@1 $hitAt1/20", hitAt1 >= 18) // pass bar: ≥90 %
        assertTrue("latency $ms ms/query", ms < 200)
        assertEquals("Manual.pdf p.${facts[0].third} · Section ${facts[0].third} overview", st.search(facts[0].first, 1)[0].cite)
    }

    @Test fun idempotentAddAndRemove() {
        val st = KnowledgeStore(Files.createTempDirectory("kb").toFile())
        val pages = listOf(PageText(0, "Syntra stores notes about the Dharashiv bakery advertisement script and its budget."))
        val a = st.add("n.md", "x", pages, null); val b = st.add("n.md", "x", pages, null)
        assertEquals(a.id, b.id); assertEquals(1, st.docs.size)
        st.remove(a.id); assertTrue(st.search("bakery budget").isEmpty())
    }

    @Test fun denseRetrievalFindsParaphrase() {
        // Toy embedder: maps synonyms to the same axis, so only meaning (not words) can match.
        val axes = mapOf("car" to 0, "automobile" to 0, "vehicle" to 0, "food" to 1, "meal" to 1, "dinner" to 1)
        val emb = object : Embedder {
            override val model = "toy"
            fun v(s: String) = FloatArray(2).also { f -> s.lowercase().split(Regex("\\W+")).forEach { w -> axes[w]?.let { f[it] += 1f } } }
                .let { f -> val n = kotlin.math.sqrt(f.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f); FloatArray(2) { f[it] / n } }
            override fun query(text: String) = v(text)
            override fun passage(title: String, text: String) = v(text)
        }
        val st = KnowledgeStore(Files.createTempDirectory("kb").toFile())
        st.add("a", "x", listOf(PageText(0, "The automobile needs new tyres before the long road trip next week.")), emb)
        st.add("b", "x", listOf(PageText(0, "Dinner is lentil soup with rice and a side of pickled mango.")), emb)
        val h = st.search("which vehicle", 1, emb)
        assertTrue(h.single().chunk.text.startsWith("The automobile")); assertEquals(0, h.single().denseRank)
    }

    @Test fun rrfAndGate() {
        assertEquals(2, rrf(listOf(1, 2), listOf(2, 3))[0].first)
        val st = KnowledgeStore(Files.createTempDirectory("kb").toFile())
        st.add("n", "x", listOf(PageText(0, "Hi there. The coffin comedy short has a money dispute between two brothers.")), null)
        assertFalse(st.search("hi").any { relevantHit("hi", it) })
        assertTrue(st.search("coffin money dispute").any { relevantHit("coffin money dispute", it) })
    }
}
