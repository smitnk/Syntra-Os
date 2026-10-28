package com.syntra.os.agent

import com.syntra.os.core.Skill
import com.syntra.os.core.httpCall

object Skills {
    val builtIn = listOf(
        Skill("Researcher", "Multi-source web research with cited findings",
            "Break the question down, fetch several sources with web_fetch, cross-check claims, save key findings to memory, and cite URLs in the answer."),
        Skill("Android Engineer", "Kotlin/Compose engineering and GitHub workflows",
            "Act as a senior Android engineer. Read code through the github tool before proposing changes; prefer small, testable diffs."),
        Skill("Story Keeper", "Tracks characters, scripts and story continuity in memory",
            "For creative work, store characters, places and plot points as linked memory entities and check them before writing new scenes."),
        Skill("UI Critic", "Reviews interfaces for hierarchy, contrast, spacing and touch targets",
            "Critique UI concretely: visual hierarchy, contrast, spacing rhythm, 48dp touch targets, small-screen behaviour. Give fixes, not adjectives."),
    )

    /** github.com/.../blob/... -> raw URL, so Agency-Agents style role files install directly. */
    fun rawUrl(url: String): String =
        if ("github.com" in url && "/blob/" in url)
            url.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/")
        else url

    /** Parses markdown with optional `---` frontmatter (name/description), the format Agency Agents uses. */
    fun parse(text: String, source: String): Skill {
        var name = source.substringAfterLast('/').removeSuffix(".md")
        var desc = ""
        var body = text
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end > 0) {
                text.substring(3, end).lines().forEach { l ->
                    val k = l.substringBefore(':').trim()
                    val v = l.substringAfter(':', "").trim().trim('"')
                    if (k == "name" && v.isNotBlank()) name = v
                    if (k == "description") desc = v
                }
                body = text.substring(end + 4).trim()
            }
        }
        // Untrusted until the user reads it and switches it on.
        return Skill(name, desc.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120) }, body, source, enabled = false)
    }

    suspend fun install(url: String): Skill {
        val raw = rawUrl(url.trim())
        require(raw.startsWith("https://")) { "Skill URL must be https" }
        val r = httpCall(raw)
        if (r.code != 200) error("HTTP ${r.code}")
        require(r.body.length < 200_000) { "Skill file too large" }
        return parse(r.body, raw)
    }
}
