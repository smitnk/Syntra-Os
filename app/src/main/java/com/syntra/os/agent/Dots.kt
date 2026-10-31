package com.syntra.os.agent

import com.syntra.os.core.ModelRole
import kotlinx.serialization.Serializable

/**
 * Dots (ported from CopilotKit/OpenDots): named specialist agents, each with its own instructions,
 * permitted tool groups, optional model, and its own workspace folder. Call one in chat with "@name task".
 */
@Serializable
data class Dot(
    val id: String,
    val name: String,
    val instructions: String,
    /** Route names the Dot may use (WEB, PHONE, CODE, AUTOMATION, GITHUB, TEAM). Memory/knowledge/review are always on. */
    val routes: Set<String> = setOf("WEB"),
    /** Model that leads this Dot's runs; null = the main model. */
    val role: ModelRole? = null,
    val memoryAllowed: Boolean = true,
    val created: Long = System.currentTimeMillis(),
) {
    val slug get() = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { id }

    fun allowedRoutes(): Set<Route> = Route.entries.filter { it.name in routes }.toSet()

    /** Every tool this Dot may call right now; checked again before each call so revoked permissions stop a run. */
    fun allows(tool: String): Boolean = tool in DOT_CORE || allowedRoutes().any { tool in it.tools } ||
        (Route.CODE in allowedRoutes() && tool in com.syntra.os.ext.ExtRuntime.tools) ||
        (Route.AUTOMATION in allowedRoutes() && tool.startsWith("mcp__")) ||
        (memoryAllowed && tool in setOf("memory_search", "memory_remember", "memory_link"))

    companion object {
        val DOT_CORE = setOf("knowledge_search", "knowledge_add", "review_page", "learn")

        val PRESETS = listOf(
            Dot("scout", "Scout", "You are Scout, a research specialist. Search, read the best sources, and answer with the facts and their links. " +
                "When asked to save findings, draft them and call review_page.", setOf("WEB")),
            Dot("writer", "Writer", "You are Writer. Turn notes and findings into clear, well-structured drafts in the user's voice. " +
                "Always show the draft with review_page before saving.", emptySet()),
            Dot("coder", "Coder", "You are Coder, a careful engineer working in Syntra's Linux sandbox. Plan, write, run and fix until it works.",
                setOf("CODE", "GITHUB")),
        )

        /** "@scout find X" → (scout, "find X"). Matches by slug or name prefix (≥3 chars). */
        fun mention(text: String, dots: List<Dot>): Pair<Dot, String>? {
            if (!text.startsWith("@")) return null
            val n = text.drop(1).substringBefore(' ').lowercase()
            val d = dots.firstOrNull { it.slug == n } ?: dots.firstOrNull { n.length >= 3 && it.slug.startsWith(n) } ?: return null
            return d to text.substringAfter(' ', "").trim()
        }
    }
}
