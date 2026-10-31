package com.syntra.os.local

import com.syntra.os.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What Syntra learned about a loaded model (GGUF metadata + projector). */
data class Capabilities(
    val arch: String, val contextTrain: Int, val params: Long, val description: String,
    val hasTemplate: Boolean, val vision: Boolean, val fileBytes: Long,
) {
    val isLfm get() = arch.lowercase().startsWith("lfm")
    fun summary() = buildString {
        append(arch.ifBlank { "unknown arch" }); append(" · ")
        if (params > 0) append("%.1fB params · ".format(params / 1e9))
        append("ctx ${contextTrain / 1024}k · ")
        append(if (vision) "vision · " else "text · ")
        append("${fileBytes / 1_000_000} MB")
        if (!hasTemplate) append(" · no chat template (ChatML used)")
    }
}

/**
 * Prompting for any GGUF model: the model's own chat template (via llama.cpp) plus a plain-text tool
 * protocol that most instruct models follow. Replies are parsed by [LfmPrompt.parse], which accepts
 * `<tool_call>{json}</tool_call>` (Qwen/Hermes style), LFM3 markup and the looser fallbacks.
 */
object GenericPrompt {
    fun toolBlock(tools: JsonArray) = buildString {
        append("\n\n# Tools\nYou can call these tools. To call one, reply with only:\n")
        append("<tool_call>{\"name\": \"tool_name\", \"arguments\": {…}}</tool_call>\n")
        append("You may emit several <tool_call> blocks. After the results arrive, continue or answer in plain text.\n")
        tools.forEach { append(LfmPrompt.compactTool(it)).append('\n') }
    }

    /** Role/content pairs in the shape every chat template understands (tool results become user turns). */
    fun flatten(messages: List<JsonObject>, tools: JsonArray?, mediaMarker: String?): Pair<List<String>, List<String>> {
        val roles = mutableListOf<String>(); val contents = mutableListOf<String>()
        val lastUser = messages.indexOfLast { it.str("role") == "user" }
        messages.forEachIndexed { i, m ->
            var role = m.str("role") ?: "user"
            var c = LfmPrompt.textOf(m)
            when (role) {
                "system" -> if (tools != null && tools.isNotEmpty()) c += toolBlock(tools)
                "assistant" -> c = m.str("raw") ?: (c + (m["tool_calls"] as? JsonArray).orEmpty().joinToString("") { tc ->
                    val f = tc.jsonObject["function"]?.jsonObject
                    "\n<tool_call>{\"name\": \"${f?.str("name")}\", \"arguments\": ${f?.str("arguments") ?: "{}"}}</tool_call>"
                })
                "tool" -> { role = "user"; c = "<tool_response>\n$c\n</tool_response>" }
                else -> if (mediaMarker != null && i == lastUser) c = "$mediaMarker\n$c"
            }
            // Templates expect alternation; merge consecutive same-role turns (e.g. several tool results).
            if (roles.isNotEmpty() && roles.last() == role && role != "system") contents[contents.size - 1] += "\n\n$c"
            else { roles += role; contents += c }
        }
        return roles to contents
    }

    fun chatMl(roles: List<String>, contents: List<String>) =
        roles.indices.joinToString("") { "<|im_start|>${roles[it]}\n${contents[it]}<|im_end|>\n" } + "<|im_start|>assistant\n"

    fun render(handle: Long, messages: List<JsonObject>, tools: JsonArray?, mediaMarker: String?): RenderedPrompt {
        val (roles, contents) = flatten(messages, tools, mediaMarker)
        fun fmt(r: List<String>, c: List<String>) = LlamaNative.applyTemplate(handle, r.toTypedArray(), c.toTypedArray()) ?: chatMl(r, c)
        val text = fmt(roles, contents)
        // Stable prefix = what two different user turns after the same system prompt have in common.
        val stable = if (roles.firstOrNull() == "system") {
            val a = fmt(listOf("system", "user"), listOf(contents[0], "a")).toByteArray()
            val b = fmt(listOf("system", "user"), listOf(contents[0], "b")).toByteArray()
            var k = 0; while (k < a.size && k < b.size && a[k] == b[k]) k++
            val t = text.toByteArray()
            if (k <= t.size && (0 until k).all { t[it] == a[it] }) k else 0
        } else 0
        return RenderedPrompt(text, stable)
    }
}
