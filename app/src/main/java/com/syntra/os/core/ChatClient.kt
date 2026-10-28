package com.syntra.os.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ToolCall(val id: String, val name: String, val argsJson: String)
class ChatReply(val content: String, val toolCalls: List<ToolCall>, val rawMessage: JsonObject)

/** OpenAI-compatible /chat/completions. One adapter covers every provider that speaks this protocol. */
object ChatClient {
    suspend fun complete(provider: Provider, model: String, messages: List<JsonObject>, tools: JsonArray?): ChatReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            if (tools != null && tools.isNotEmpty()) put("tools", tools)
        }
        val headers = buildMap {
            if (provider.apiKey.isNotBlank()) put("Authorization", "Bearer ${provider.apiKey}")
            put("X-Title", "Syntra OS")
        }
        val r = httpCall(provider.baseUrl.trimEnd('/') + "/chat/completions", "POST", body, headers)
        if (r.code !in 200..299) error("${provider.name} HTTP ${r.code}: ${r.body.take(400)}")
        return parse(r.body)
    }

    fun parse(body: String): ChatReply {
        val msg = json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        val calls = (msg["tool_calls"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            val fn = o["function"]!!.jsonObject
            ToolCall(o.str("id") ?: "call", fn.str("name").orEmpty(), fn.str("arguments") ?: "{}")
        }
        return ChatReply(msg.str("content").orEmpty(), calls, msg)
    }
}

fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

fun toolResult(id: String, content: String) =
    buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", content) }
