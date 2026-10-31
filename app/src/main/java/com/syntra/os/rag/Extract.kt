package com.syntra.os.rag

import android.content.Context
import com.syntra.os.local.LlamaNative
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Text extraction for the formats people actually drop in: PDF (per page), DOCX, HTML, and plain text/code. */
object Extract {
    private val TEXT = setOf("txt", "md", "markdown", "csv", "json", "yaml", "yml", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "xml", "log", "srt")

    fun supported(name: String) = name.substringAfterLast('.', "").lowercase().let { it in TEXT || it in setOf("pdf", "docx", "html", "htm") }

    fun pages(context: Context, name: String, input: InputStream): List<PageText> = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input).use { d ->
                val s = PDFTextStripper()
                (1..d.numberOfPages).map { p -> s.startPage = p; s.endPage = p; PageText(p, s.getText(d)) }
            }
        }
        "docx" -> listOf(PageText(0, docx(input)))
        "html", "htm" -> listOf(PageText(0, Jsoup.parse(input.reader().readText()).let { it.select("script,style,nav,footer").remove(); it.body()?.wholeText() ?: it.text() }))
        else -> listOf(PageText(0, input.reader().readText()))
    }

    /** word/document.xml → paragraphs (one blank line between <w:p>, so the chunker sees paragraph breaks). */
    private fun docx(input: InputStream): String {
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    val xml = z.readBytes().decodeToString()
                    return xml.split("</w:p>").joinToString("\n\n") { p ->
                        Regex("<w:t[^>]*>([^<]*)</w:t>").findAll(p).joinToString("") { it.groupValues[1] }
                    }.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                }
            }
        }
        error("not a .docx file")
    }
}

/** EmbeddingGemma (multilingual, 768-d) via llama.cpp. Prefixes follow the model card's retrieval prompts. */
class LlamaEmbedder(file: File, threads: Int) : Embedder, AutoCloseable {
    override val model: String = file.name
    private val h = LlamaNative.loadEmbedder(file.path, threads)
    @Synchronized override fun query(text: String) = LlamaNative.embed(h, "task: search result | query: $text")
    @Synchronized override fun passage(title: String, text: String) = LlamaNative.embed(h, "title: ${title.ifBlank { "none" }} | text: $text")
    @Synchronized override fun close() = LlamaNative.free(h)
}

/** Loads the embedding model on first use, so idle app RAM stays low. */
class LazyEmbedder(private val file: File, private val threads: Int) : Embedder {
    override val model: String = file.name
    private var e: LlamaEmbedder? = null
    @Synchronized private fun get() = e ?: LlamaEmbedder(file, threads).also { e = it }
    override fun query(text: String) = get().query(text)
    override fun passage(title: String, text: String) = get().passage(title, text)
    /** Frees the model; the next search reloads it (mmap, ~1 s). */
    @Synchronized fun release() { e?.close(); e = null }
}

/** App-wide knowledge base (like ExtRuntime): the store plus the optional embedder. */
object Knowledge {
    @Volatile var store: KnowledgeStore? = null
    @Volatile var embedder: Embedder? = null
    fun releaseEmbedder() { (embedder as? LazyEmbedder)?.release() }

    fun search(query: String, k: Int = 5) = store?.search(query, k, embedder).orEmpty()

    /** For automatic context: drop hits that only share a stray word with the request (e.g. "hi" chats). */
    fun relevant(query: String, k: Int = 4) = search(query, k).filter { relevantHit(query, it) }
}
