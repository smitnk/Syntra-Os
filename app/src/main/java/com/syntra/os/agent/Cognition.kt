package com.syntra.os.agent

/**
 * The fixed routine around a small model, like a person working: recall what you know → notice what you don't →
 * look it up → act → check → learn. These decisions are code, because a 1–2B model can't reliably judge its own gaps.
 */
object Cognition {
    private val FRESH = listOf("news", "today", "latest", "current", "currently", "now", "recent", "this week", "this month", "update",
        "price", "rate", "weather", "score", "result", "election", "war", "conflict", "happening", "live", "stock", "trend",
        "khabar", "samachar", "aaj", "abhi", "taaza")
    private val ASK = Regex("^(who|what|when|where|why|how|which|is|are|does|did|tell me|explain|details|detail|info|information|give me|need|find|search|look up)\\b")

    /** Needs information that changes over time, so memory alone can't be trusted. */
    fun needsFresh(goal: String): Boolean {
        val t = " " + goal.lowercase() + " "
        return FRESH.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) } || Regex("\\b20[2-9]\\d\\b").containsMatchIn(t)
    }

    /** A request for information (not an action, not small talk). */
    fun isQuestion(goal: String): Boolean {
        val t = goal.trim().lowercase()
        return t.endsWith("?") || ASK.containsMatchIn(t) || Regex("\\b(details?|about|regarding|information)\\b").containsMatchIn(t)
    }

    /**
     * Research before answering when the facts are time-sensitive, or when it's a factual question the user's notes
     * and documents don't cover. [known] = relevant passages already found; [learnedFresh] = a recent learned note matched.
     */
    fun shouldResearch(goal: String, webAllowed: Boolean, known: Int, learnedFresh: Boolean): Boolean {
        if (!webAllowed || goal.trim().length < 6) return false
        if (needsFresh(goal)) return !learnedFresh
        return isQuestion(goal) && known == 0 && words(goal).size >= 3
    }

    fun words(s: String) = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(s.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()

    /** Same answer again (small models copy their last reply): word overlap ≥ 70 %. */
    fun isRepeat(answer: String, previous: String?): Boolean {
        if (previous.isNullOrBlank() || answer.length < 80) return false
        val a = words(answer); val b = words(previous)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size) >= 0.7
    }

    /** Earlier assistant replies shrink to their first line, so the model reads the topic but can't copy the text. */
    fun shrinkOldAnswer(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(160) +
        if (text.length > 160) " …(earlier answer, shortened)" else ""
}
