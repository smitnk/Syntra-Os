package com.syntra.os.rag

import com.syntra.os.core.json
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

/**
 * On-device RAG: documents → page-aware chunks → hybrid retrieval (BM25 + optional dense embeddings, fused with RRF).
 * Pure Kotlin so it is unit-testable; the embedder is injected (llama.cpp on device).
 */

@Serializable
data class Doc(val id: String, val title: String, val source: String, val chunks: Int, val pages: Int = 0,
               val added: Long = System.currentTimeMillis(), val embedModel: String = "")

@Serializable
data class Chunk(val id: String, val docId: String, val text: String, val page: Int = 0, val section: String = "")

/** A page (or the whole file when it has no pages) of extracted text. */
data class PageText(val page: Int, val text: String)

data class Hit(val chunk: Chunk, val doc: Doc, val score: Double, val bm25Rank: Int?, val denseRank: Int?) {
    /** Citation shown to the model and the user, e.g. "Manual.pdf p.12 · Safety". */
    val cite get() = buildString {
        append(doc.title); if (chunk.page > 0) append(" p.${chunk.page}"); if (chunk.section.isNotBlank()) append(" · ${chunk.section.take(60)}")
    }
}

object Chunker {
    // ~900 chars ≈ 220 tokens: small enough for 512-token embedders and to fit 4–5 chunks in a phone-sized prompt.
    const val SIZE = 900
    const val OVERLAP = 150
    private val heading = Regex("^(#{1,6}\\s+.+|[A-Z0-9][^.!?]{2,70}:?|\\d+(\\.\\d+)*\\s+[^.!?]{2,70})$")

    fun split(docId: String, pages: List<PageText>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var section = ""
        for (p in pages) {
            val buf = StringBuilder()
            fun flush() {
                val t = buf.toString().trim()
                if (t.length >= 20) out += Chunk("$docId:${out.size}", docId, t, p.page, section)
                // Carry a sentence-aligned tail into the next chunk so facts on a boundary survive.
                val tail = t.takeLast(OVERLAP).let { s -> s.indexOf(". ").takeIf { it in 0 until s.length - 2 }?.let { s.substring(it + 2) } ?: s }
                buf.clear(); if (t.length > OVERLAP) buf.append(tail).append(' ')
            }
            for (para in p.text.replace("\r", "").split(Regex("\n\\s*\n"))) {
                val clean = para.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
                if (clean.isEmpty()) continue
                if (clean.length < 80 && heading.matches(clean)) {
                    if (buf.length > OVERLAP + 40) flush()
                    section = clean.trimStart('#', ' ').trimEnd(':')
                    buf.append(clean).append('\n'); continue
                }
                for (piece in pieces(clean)) {
                    if (buf.length + piece.length > SIZE && buf.length > OVERLAP) flush()
                    buf.append(piece).append(' ')
                }
            }
            if (buf.toString().trim().length > OVERLAP || out.none { it.page == p.page }) flush()
        }
        return out.distinctBy { it.text }
    }

    /** Paragraph, else sentences, else hard cuts — never longer than [SIZE]. */
    private fun pieces(s: String): List<String> = if (s.length <= SIZE) listOf(s)
        else s.split(Regex("(?<=[.!?।])\\s+")).flatMap { if (it.length <= SIZE) listOf(it) else it.chunked(SIZE) }
}

object Tokens {
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "it", "this",
        "that", "with", "as", "at", "by", "from", "what", "which", "who", "how", "does", "do", "i", "you", "me", "my", "about", "can", "tell")
    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+") // \p{M} keeps Devanagari vowel signs inside words

    fun of(s: String): List<String> = word.findAll(s.lowercase()).map { stem(it.value) }.filter { it.length > 1 && it !in STOP }.toList()
    private fun stem(w: String) = when { w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"; w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1); else -> w }
}

/** BM25 over all chunks, rebuilt in memory on load (a few thousand chunks is milliseconds). */
class Bm25(private val chunks: List<Chunk>, private val k1: Double = 1.2, private val b: Double = 0.75) {
    private val tf = chunks.map { c -> Tokens.of(c.section + " " + c.text).groupingBy { it }.eachCount() }
    private val len = tf.map { it.values.sum() }
    private val avg = len.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
    private val df = HashMap<String, Int>().apply { tf.forEach { m -> m.keys.forEach { merge(it, 1, Int::plus) } } }

    fun search(query: String, k: Int): List<Pair<Int, Double>> {
        val q = Tokens.of(query).distinct()
        if (q.isEmpty()) return emptyList()
        val n = chunks.size
        return tf.indices.mapNotNull { i ->
            var s = 0.0
            for (t in q) {
                val f = tf[i][t] ?: continue
                val idf = ln(1 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * len[i] / avg))
            }
            if (s > 0) i to s else null
        }.sortedByDescending { it.second }.take(k)
    }
}

/** Reciprocal-rank fusion: robust to BM25 and cosine scores living on different scales. */
fun rrf(vararg lists: List<Int>, k: Int = 60): List<Pair<Int, Double>> {
    val s = HashMap<Int, Double>()
    lists.forEach { l -> l.forEachIndexed { r, i -> s.merge(i, 1.0 / (k + r + 1), Double::plus) } }
    return s.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/** Embeddings for search queries vs. stored passages (EmbeddingGemma-style task prefixes). */
interface Embedder {
    val model: String
    fun query(text: String): FloatArray
    fun passage(title: String, text: String): FloatArray
}

class KnowledgeStore(private val dir: File) {
    init { dir.mkdirs() }
    private val indexFile = File(dir, "index.json")
    @Volatile var docs: List<Doc> = load(); private set
    @Volatile private var chunks: List<Chunk> = emptyList()
    @Volatile private var vectors: Map<String, FloatArray> = emptyMap()
    @Volatile private var bm25: Bm25? = null

    init { reload() }

    private fun load(): List<Doc> = runCatching { json.decodeFromString<List<Doc>>(indexFile.readText()) }.getOrDefault(emptyList())

    @Synchronized private fun reload() {
        chunks = docs.flatMap { d -> runCatching { json.decodeFromString<List<Chunk>>(File(dir, "${d.id}.chunks.json").readText()) }.getOrDefault(emptyList()) }
        vectors = docs.flatMap { d -> readVectors(d.id).toList() }.toMap()
        bm25 = Bm25(chunks)
    }

    fun chunkCount() = chunks.size

    /**
     * Adds a document. Same content twice → same id → no duplicate (idempotent). Embeds when [embedder] is given.
     * Returns the stored Doc.
     */
    @Synchronized fun add(title: String, source: String, pages: List<PageText>, embedder: Embedder?, progress: (Int, Int) -> Unit = { _, _ -> }): Doc {
        val id = sha(pages.joinToString("\u0000") { it.text }).take(16)
        docs.firstOrNull { it.id == id }?.let { if (embedder == null || it.embedModel == embedder.model) return it }
        val cs = Chunker.split(id, pages)
        require(cs.isNotEmpty()) { "No readable text in $title (scanned PDF or image? Text extraction found nothing)" }
        File(dir, "$id.chunks.json").writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Chunk.serializer()), cs))
        if (embedder != null) {
            val vs = cs.mapIndexed { i, c -> progress(i + 1, cs.size); c.id to embedder.passage(title, c.text) }
            writeVectors(id, vs)
        }
        val doc = Doc(id, title, source, cs.size, pages.count { it.page > 0 }, embedModel = embedder?.model.orEmpty())
        docs = docs.filter { it.id != id } + doc
        save(); reload()
        return doc
    }

    @Synchronized fun remove(id: String) {
        docs = docs.filter { it.id != id }
        File(dir, "$id.chunks.json").delete(); File(dir, "$id.vec").delete()
        save(); reload()
    }

    /** Docs whose vectors are missing or from another model (after the embedder changed). */
    fun needsEmbedding(model: String) = docs.filter { it.embedModel != model }

    @Synchronized fun embedMissing(embedder: Embedder, progress: (String) -> Unit) {
        for (d in needsEmbedding(embedder.model)) {
            val cs = chunks.filter { it.docId == d.id }
            writeVectors(d.id, cs.mapIndexed { i, c -> if (i % 10 == 0) progress("${d.title}: ${i + 1}/${cs.size}"); c.id to embedder.passage(d.title, c.text) })
            docs = docs.map { if (it.id == d.id) it.copy(embedModel = embedder.model) else it }
        }
        save(); reload()
    }

    /**
     * Hybrid search: BM25 top-40 + cosine top-40 (only vectors from the current [embedder] model), fused with RRF.
     * [docIds] restricts the search (metadata filter).
     */
    fun search(query: String, k: Int = 5, embedder: Embedder? = null, docIds: Set<String>? = null): List<Hit> {
        val cs = chunks; val index = bm25 ?: return emptyList()
        if (cs.isEmpty() || query.isBlank()) return emptyList()
        val allowed = { i: Int -> docIds == null || cs[i].docId in docIds }
        val lex = index.search(query, 80).filter { allowed(it.first) }.take(40).map { it.first }
        val okDocs = docs.filter { embedder != null && it.embedModel == embedder.model }.map { it.id }.toSet()
        val dense = if (embedder == null || okDocs.isEmpty()) emptyList() else {
            val q = embedder.query(query)
            cs.indices.filter { allowed(it) && cs[it].docId in okDocs }.mapNotNull { i -> vectors[cs[i].id]?.let { i to dot(q, it) } }
                .filter { it.second > 0.25 } // below this, cosine matches are noise for normalised embedders
                .sortedByDescending { it.second }.take(40).map { it.first }
        }
        val byId = docs.associateBy { it.id }
        return rrf(lex, dense).take(k).mapNotNull { (i, s) ->
            byId[cs[i].docId]?.let { Hit(cs[i], it, s, lex.indexOf(i).takeIf { r -> r >= 0 }, dense.indexOf(i).takeIf { r -> r >= 0 }) }
        }
    }

    private fun save() {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Doc.serializer()), docs))
        tmp.renameTo(indexFile)
    }

    private fun writeVectors(id: String, vs: List<Pair<String, FloatArray>>) =
        DataOutputStream(File(dir, "$id.vec").outputStream().buffered()).use { o ->
            o.writeInt(vs.size)
            vs.forEach { (cid, v) -> o.writeUTF(cid); o.writeInt(v.size); v.forEach(o::writeFloat) }
        }

    private fun readVectors(id: String): Map<String, FloatArray> = runCatching {
        DataInputStream(File(dir, "$id.vec").inputStream().buffered()).use { i ->
            (0 until i.readInt()).associate { i.readUTF() to FloatArray(i.readInt()) { _ -> i.readFloat() } }
        }
    }.getOrDefault(emptyMap())

    companion object {
        fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }
        fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Context block for the model: numbered passages it must cite as [n]. */
        fun contextBlock(hits: List<Hit>, maxChars: Int = 3600): String {
            var used = 0
            return hits.mapIndexedNotNull { n, h ->
                val t = h.chunk.text.take(1100)
                if (used + t.length > maxChars) null else { used += t.length; "[${n + 1}] (${h.cite})\n$t" }
            }.joinToString("\n\n")
        }
    }
}

/** For automatic context: a hit must share most query words, or be a top semantic match. */
fun relevantHit(query: String, h: Hit): Boolean {
    val q = Tokens.of(query).toSet()
    if (q.isEmpty()) return false
    val c = Tokens.of(h.chunk.section + " " + h.chunk.text).toSet()
    return h.denseRank != null && h.denseRank < 3 || q.count { it in c } * 2 >= q.size && q.count { it in c } >= minOf(2, q.size)
}
