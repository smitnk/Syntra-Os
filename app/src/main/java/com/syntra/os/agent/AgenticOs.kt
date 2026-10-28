package com.syntra.os.agent

import android.content.Context
import com.syntra.os.core.Skill
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Level 4" agentic-OS workspace: static context the agent always reads, learnings that grow
 * from your corrections, and predictable output folders per skill. Mirrored into Ubuntu at /root/agentic-os.
 */
class AgenticOs(ctx: Context) {
    val root = File(ctx.filesDir, "agentic-os").apply { mkdirs() }
    val identity = File(root, "context/identity.md")
    val learnings = File(root, "memory/learnings.md")
    val outputs = File(root, "outputs")

    init {
        identity.parentFile?.mkdirs(); learnings.parentFile?.mkdirs(); outputs.mkdirs()
        if (!identity.exists()) identity.writeText(
            "# Who I am\n(Edit in Setup → Agentic OS: your name, work, goals, voice and style. Syntra reads this on every task.)\n")
        if (!learnings.exists()) learnings.writeText("# Learnings\nRules Syntra learned from your corrections.\n")
    }

    /** Stable block for the system prompt (part of the cached prefix, so keep it short). */
    fun context(): String = buildString {
        identity.readText().take(1500).takeIf { it.isNotBlank() }?.let { appendLine("## About the user (context/identity.md)\n$it") }
        learnings.readText().lines().filter { it.startsWith("- ") }.takeLast(25).takeIf { it.isNotEmpty() }?.let {
            appendLine("## Rules learned from the user (follow them)"); it.forEach(::appendLine)
        }
    }

    fun learn(rule: String): String {
        val r = rule.trim().removePrefix("- ")
        require(r.isNotBlank()) { "rule required" }
        if (learnings.readText().contains(r)) return "Already known."
        learnings.appendText("- $r\n")
        return "Learned: $r"
    }

    fun saveOutput(folder: String, title: String, content: String): String {
        val dir = File(outputs, slug(folder).ifBlank { "general" }).apply { mkdirs() }
        val f = File(dir, SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + "_" + slug(title).take(40).ifBlank { "output" } + ".md")
        f.writeText(content)
        return "Saved outputs/${dir.name}/${f.name}"
    }

    companion object {
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        /** "/linkedin write about X" → (skill, "write about X"); "/code …" is handled as Code mode. */
        fun parseSlash(text: String, skills: List<Skill>): Pair<Skill?, String>? {
            if (!text.startsWith("/")) return null
            val name = text.drop(1).substringBefore(' ').lowercase()
            val rest = text.substringAfter(' ', "").trim()
            return skills.firstOrNull { slug(it.name) == name || slug(it.name).startsWith(name) && name.length >= 3 } to rest
        }
    }
}
