package com.syntra.os.media

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Markdown → real PDF / DOCX files. Supports the subset models actually write: #-headings, bullets, numbered lists, **bold**, paragraphs. */
object Documents {
    sealed interface Block { val text: String }
    data class Heading(val level: Int, override val text: String) : Block
    data class Bullet(override val text: String, val number: Int? = null) : Block
    data class Para(override val text: String) : Block

    fun parse(md: String): List<Block> = md.replace("\r", "").split(Regex("\n\\s*\n")).flatMap { para ->
        val lines = para.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) emptyList()
        else if (lines.all { Regex("^\\s*([-*+]|\\d+[.)])\\s+.*").matches(it) || it.startsWith("#") }) lines.map(::line)
        else if (lines.first().startsWith("#")) listOf(line(lines.first())) + lines.drop(1).takeIf { it.isNotEmpty() }?.let { listOf(Para(it.joinToString(" "))) }.orEmpty()
        else listOf(Para(lines.joinToString(" ")))
    }

    private fun line(l: String): Block {
        Regex("^(#{1,6})\\s+(.*)").find(l)?.let { return Heading(it.groupValues[1].length, it.groupValues[2]) }
        Regex("^\\s*(\\d+)[.)]\\s+(.*)").find(l)?.let { return Bullet(it.groupValues[2], it.groupValues[1].toInt()) }
        Regex("^\\s*[-*+]\\s+(.*)").find(l)?.let { return Bullet(it.groupValues[1]) }
        return Para(l)
    }

    /** "**a** b" → [(a, bold), (" b", plain)] */
    fun runs(s: String): List<Pair<String, Boolean>> = s.split("**").mapIndexedNotNull { i, t -> if (t.isEmpty()) null else t to (i % 2 == 1) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun writeDocx(md: String, out: File) {
        val body = parse(md).joinToString("") { b ->
            val (style, prefix) = when (b) { is Heading -> "Heading${b.level}" to ""; is Bullet -> "ListParagraph" to (b.number?.let { "$it. " } ?: "• "); is Para -> "" to "" }
            val pPr = if (style.isEmpty()) "" else "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>"
            val size = (b as? Heading)?.let { "<w:sz w:val=\"${maxOf(24, 40 - it.level * 4)}\"/>" }.orEmpty()
            "<w:p>$pPr" + runs(prefix + b.text).joinToString("") { (t, bold) ->
                "<w:r><w:rPr>${if (bold || b is Heading) "<w:b/>" else ""}$size</w:rPr><w:t xml:space=\"preserve\">${esc(t)}</w:t></w:r>" } + "</w:p>"
        }
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>""")
        }
    }

    /** A4 PDF via Android's PdfDocument; wraps text with StaticLayout (handles Devanagari shaping) and breaks pages. */
    fun writePdf(md: String, out: File) {
        val doc = android.graphics.pdf.PdfDocument()
        val w = 595; val h = 842; val margin = 48f; val width = (w - 2 * margin).toInt()
        var pageNo = 0
        var page: android.graphics.pdf.PdfDocument.Page? = null
        var y = 0f
        fun newPage() { page?.let(doc::finishPage); page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, ++pageNo).create()); y = margin }
        newPage()
        for (b in parse(md)) {
            val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = when (b) { is Heading -> maxOf(13f, 24f - b.level * 2.5f); else -> 11f }
                isFakeBoldText = b is Heading
            }
            val text = android.text.SpannableStringBuilder().apply {
                if (b is Bullet) append(b.number?.let { "$it. " } ?: "•  ")
                runs(b.text).forEach { (t, bold) -> val s = length; append(t)
                    if (bold) setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, length, 0) }
            }
            val indent = if (b is Bullet) 14 else 0
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width - indent).setLineSpacing(2f, 1f).build()
            var line = 0
            while (line < layout.lineCount) { // split long paragraphs across pages line by line
                val room = h - margin - y
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= room) last++
                if (last == line) { newPage(); continue }
                val c = page!!.canvas
                c.save(); c.translate(margin + indent, y - layout.getLineTop(line))
                c.clipRect(0f, layout.getLineTop(line).toFloat(), width.toFloat(), layout.getLineBottom(last - 1).toFloat())
                layout.draw(c); c.restore()
                y += layout.getLineBottom(last - 1) - layout.getLineTop(line)
                line = last
                if (line < layout.lineCount) newPage()
            }
            y += if (b is Heading) 10f else 8f
        }
        page?.let(doc::finishPage)
        out.parentFile?.mkdirs()
        out.outputStream().use(doc::writeTo)
        doc.close()
    }
}

/** ffmpeg in Syntra's Linux: arguments are validated so a model can't smuggle shell commands. */
object Media {
    private val SAFE = Regex("[A-Za-z0-9_.,:=+\\-/%\\[\\];@*]+")

    /** Workspace-relative path → its path inside Linux. Rejects escapes. */
    fun linuxPath(rel: String): String {
        val p = rel.trim().trimStart('/')
        require(p.isNotEmpty() && ".." !in p.split('/') && SAFE.matches(p)) { "use a simple workspace path (letters, digits, - _ . /): $rel" }
        return "/root/workspace/$p"
    }

    fun command(inputs: List<String>, output: String, args: String): String {
        require(inputs.isNotEmpty()) { "at least one input file" }
        val a = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        a.forEach { require(SAFE.matches(it) && !it.startsWith("/") && ".." !in it) { "unsafe ffmpeg argument: $it" } }
        val ins = inputs.joinToString(" ") { "-i '${linuxPath(it)}'" }
        val out = linuxPath(output)
        return "command -v ffmpeg >/dev/null || (apt-get update -qq && apt-get install -y -qq ffmpeg) >/dev/null; " +
            "mkdir -p \"$(dirname '$out')\" && ffmpeg -hide_banner -loglevel error -y $ins ${a.joinToString(" ") { "'$it'" }} '$out' && ls -l '$out'"
    }
}
