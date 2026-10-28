package com.syntra.os.local

import com.syntra.os.core.ModelRole
import com.syntra.os.core.Settings
import com.syntra.os.core.http
import com.syntra.os.core.json
import com.syntra.os.core.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * OpenAI-compatible API on 127.0.0.1:8099 so Hermes, Aider, Ubuntu scripts — anything — can use Syntra's
 * on-device LFM models ("syntra", "syntra-vision", …) or a configured cloud provider ("openrouter/<model>").
 */
class LocalServer(private val engines: LocalEngines, private val settings: () -> Settings, private val key: String) {
    companion object { const val PORT = 8099 }
    private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val s = runCatching { ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return
        socket = s
        thread(name = "syntra-api", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use { handle(it) } } }
            }
        }
    }

    fun stop() { socket?.close(); socket = null }

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun read(c: Socket): Req? {
        val inp = BufferedInputStream(c.getInputStream())
        fun line(): String {
            val sb = StringBuilder()
            while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
            return sb.toString()
        }
        val first = line().split(' ')
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        require(len in 0..20_000_000) { "body too large" }
        val buf = ByteArray(len); var off = 0
        while (off < len) { val n = inp.read(buf, off, len - off); if (n < 0) break; off += n }
        return Req(first[0], first[1].substringBefore('?'), headers, String(buf, 0, off))
    }

    private fun respond(out: OutputStream, code: Int, body: String, type: String = "application/json") {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun err(out: OutputStream, code: Int, msg: String) =
        respond(out, code, buildJsonObject { putJsonObject("error") { put("message", msg); put("type", "syntra_error") } }.toString())

    private fun handle(c: Socket) {
        val r = read(c) ?: return
        val out = c.getOutputStream()
        val auth = r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: r.headers["x-api-key"]?.trim()
        if (auth != key) return err(out, 401, "invalid API key (use \$OPENAI_API_KEY from Syntra)")
        when {
            r.method == "GET" && r.path.endsWith("/models") -> respond(out, 200, models().toString())
            r.method == "POST" && r.path.endsWith("/messages") ->
                runCatching { anthropic(out, json.parseToJsonElement(r.body).jsonObject) }.onFailure { runCatching { anthropicErr(out, it.message ?: "error") } }
            r.method == "POST" && r.path.endsWith("/chat/completions") ->
                runCatching { chat(out, json.parseToJsonElement(r.body).jsonObject) }.onFailure { runCatching { err(out, 500, it.message ?: "error") } }
            else -> err(out, 404, "not found: ${r.path}")
        }
    }

    private fun models() = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            add(buildJsonObject { put("id", "syntra"); put("object", "model"); put("owned_by", "syntra-on-device") })
            engines.installedRoles().forEach { add(buildJsonObject { put("id", "syntra-${it.name.lowercase()}"); put("object", "model"); put("owned_by", "syntra-on-device") }) }
            settings().providers.filter { !it.local && it.apiKey.isNotBlank() }.forEach {
                add(buildJsonObject { put("id", "${it.id}/…"); put("object", "model"); put("owned_by", it.name) })
            }
        }
    }

    private fun chat(out: OutputStream, req: JsonObject) {
        val model = req.str("model") ?: "syntra"
        val cloud = settings().providers.firstOrNull { !it.local && model.startsWith(it.id + "/") }
        if (cloud != null) return forward(out, cloud.baseUrl, cloud.apiKey, model.removePrefix(cloud.id + "/"), req)

        val role = ModelRole.entries.firstOrNull { model.equals("syntra-${it.name}", true) }
            ?: settings().primaryRole.takeIf { settings().provider(settings().slot(it).providerId)?.local == true } ?: ModelRole.TEXT
        val messages = (req["messages"] as? JsonArray).orEmpty().map { it.jsonObject }
        val tools = req["tools"] as? JsonArray
        val stream = (req["stream"] as? JsonPrimitive)?.content == "true"
        val maxTokens = (req["max_tokens"] ?: req["max_completion_tokens"])?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 1024
        val id = "chatcmpl-${System.currentTimeMillis()}"

        if (!stream) {
            val reply = runBlocking { engines.complete(role, messages, tools, null, null, maxTokens = maxTokens.coerceIn(16, 4096)) }
            return respond(out, 200, buildJsonObject {
                put("id", id); put("object", "chat.completion"); put("created", System.currentTimeMillis() / 1000); put("model", model)
                putJsonArray("choices") { add(buildJsonObject {
                    put("index", 0); put("message", messageJson(reply.content, reply.toolCalls))
                    put("finish_reason", if (reply.toolCalls.isEmpty()) "stop" else "tool_calls")
                }) }
                putJsonObject("usage") { put("prompt_tokens", 0); put("completion_tokens", 0); put("total_tokens", 0) }
            }.toString())
        }

        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        fun send(delta: JsonObject, finish: String? = null) {
            val chunk = buildJsonObject {
                put("id", id); put("object", "chat.completion.chunk"); put("created", System.currentTimeMillis() / 1000); put("model", model)
                putJsonArray("choices") { add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }) }
            }
            out.write("data: $chunk\n\n".toByteArray()); out.flush()
        }
        send(buildJsonObject { put("role", "assistant"); put("content", "") })
        var sent = 0
        val reply = runBlocking {
            engines.complete(role, messages, tools, null, { full ->
                // Stream plain text only; stop at the first sign of tool-call markup.
                val visible = full.substringBefore("<tool").substringBefore("<think>")
                if (visible.length > sent && !full.startsWith("<")) { runCatching { send(buildJsonObject { put("content", visible.substring(sent)) }) }; sent = visible.length }
            }, maxTokens = maxTokens.coerceIn(16, 4096))
        }
        if (reply.toolCalls.isEmpty() && reply.content.length > sent) send(buildJsonObject { put("content", reply.content.substring(sent.coerceAtMost(reply.content.length))) })
        if (reply.toolCalls.isNotEmpty()) send(buildJsonObject {
            put("tool_calls", buildJsonArray { reply.toolCalls.forEachIndexed { i, t -> add(buildJsonObject {
                put("index", i); put("id", "call_${id}_$i"); put("type", "function")
                putJsonObject("function") { put("name", t.name); put("arguments", t.argsJson) }
            }) } })
        })
        send(JsonObject(emptyMap()), if (reply.toolCalls.isEmpty()) "stop" else "tool_calls")
        out.write("data: [DONE]\n\n".toByteArray()); out.flush()
    }

    // ---------------- Anthropic Messages API (for Claude Code: `claude-local`) ----------------

    private fun anthropicErr(out: OutputStream, msg: String) = respond(out, 400,
        buildJsonObject { put("type", "error"); putJsonObject("error") { put("type", "invalid_request_error"); put("message", msg) } }.toString())

    private fun blocksText(c: kotlinx.serialization.json.JsonElement?): String = when (c) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.joinToString("\n") { b -> (b as? JsonObject)?.let { o ->
            when (o.str("type")) { "text" -> o.str("text").orEmpty(); "tool_result" -> blocksText(o["content"]); else -> "" } }.orEmpty() }
        else -> ""
    }

    /** Anthropic request → OpenAI-style messages/tools (the format the engine and cloud adapters speak). */
    fun toOpenAi(req: JsonObject): Pair<List<JsonObject>, JsonArray?> {
        val msgs = mutableListOf<JsonObject>()
        blocksText(req["system"]).takeIf { it.isNotBlank() }?.let { msgs += com.syntra.os.core.message("system", it) }
        (req["messages"] as? JsonArray).orEmpty().forEach { m ->
            val o = m.jsonObject; val role = o.str("role") ?: "user"; val c = o["content"]
            val blocks = (c as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val uses = blocks.filter { it.str("type") == "tool_use" }
            val results = blocks.filter { it.str("type") == "tool_result" }
            results.forEach { tr -> msgs += com.syntra.os.core.toolResult(tr.str("tool_use_id") ?: "t", blocksText(tr["content"])) }
            val text = if (c is JsonPrimitive) c.content else blocks.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
            if (role == "assistant") msgs += buildJsonObject {
                put("role", "assistant"); put("content", text)
                if (uses.isNotEmpty()) putJsonArray("tool_calls") { uses.forEach { u -> add(buildJsonObject {
                    put("id", u.str("id") ?: "t"); put("type", "function")
                    putJsonObject("function") { put("name", u.str("name") ?: ""); put("arguments", (u["input"] ?: JsonObject(emptyMap())).toString()) }
                }) } }
            } else if (text.isNotBlank() || results.isEmpty()) msgs += com.syntra.os.core.message("user", text)
        }
        val tools = (req["tools"] as? JsonArray)?.let { ts -> JsonArray(ts.mapNotNull { it as? JsonObject }.filter { it.str("name") != null }.map { t ->
            buildJsonObject { put("type", "function"); putJsonObject("function") {
                put("name", t.str("name")!!); put("description", (t.str("description") ?: "").take(400)); put("parameters", t["input_schema"] ?: JsonObject(emptyMap()))
            } }
        }) }
        return msgs to tools
    }

    private fun anthropic(out: OutputStream, req: JsonObject) {
        val model = req.str("model") ?: "syntra"
        val (messages, tools) = toOpenAi(req)
        val maxTokens = (req["max_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1024
        val cloud = settings().providers.firstOrNull { !it.local && model.startsWith(it.id + "/") }
        val reply = runBlocking {
            if (cloud != null) com.syntra.os.core.ChatClient.complete(cloud, model.removePrefix(cloud.id + "/"), messages, tools)
            else {
                val role = ModelRole.entries.firstOrNull { model.equals("syntra-${it.name}", true) } ?: settings().primaryRole.takeIf { it != ModelRole.DECISION } ?: ModelRole.OMNI
                engines.complete(role, messages, tools, null, null, maxTokens = maxTokens.coerceIn(16, 2048))
            }
        }
        val id = "msg_${System.currentTimeMillis()}"
        val content = buildJsonArray {
            if (reply.content.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", reply.content) })
            reply.toolCalls.forEachIndexed { i, t -> add(buildJsonObject {
                put("type", "tool_use"); put("id", "toolu_${id}_$i"); put("name", t.name)
                put("input", runCatching { json.parseToJsonElement(t.argsJson) }.getOrDefault(JsonObject(emptyMap())))
            }) }
        }
        val stop = if (reply.toolCalls.isEmpty()) "end_turn" else "tool_use"
        if ((req["stream"] as? JsonPrimitive)?.content != "true") return respond(out, 200, buildJsonObject {
            put("id", id); put("type", "message"); put("role", "assistant"); put("model", model); put("content", content)
            put("stop_reason", stop); putJsonObject("usage") { put("input_tokens", 0); put("output_tokens", 0) }
        }.toString())
        // Streaming: the whole reply as one event sequence (Claude Code requires the SSE shape, not incremental tokens).
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        fun ev(type: String, data: JsonObject) { out.write("event: $type\ndata: $data\n\n".toByteArray()); out.flush() }
        ev("message_start", buildJsonObject { put("type", "message_start"); putJsonObject("message") {
            put("id", id); put("type", "message"); put("role", "assistant"); put("model", model); put("content", JsonArray(emptyList()))
            put("stop_reason", kotlinx.serialization.json.JsonNull); putJsonObject("usage") { put("input_tokens", 0); put("output_tokens", 0) } } })
        content.forEachIndexed { i, b ->
            val o = b.jsonObject
            if (o.str("type") == "text") {
                ev("content_block_start", buildJsonObject { put("type", "content_block_start"); put("index", i); putJsonObject("content_block") { put("type", "text"); put("text", "") } })
                ev("content_block_delta", buildJsonObject { put("type", "content_block_delta"); put("index", i); putJsonObject("delta") { put("type", "text_delta"); put("text", o.str("text") ?: "") } })
            } else {
                ev("content_block_start", buildJsonObject { put("type", "content_block_start"); put("index", i); putJsonObject("content_block") {
                    put("type", "tool_use"); put("id", o.str("id") ?: ""); put("name", o.str("name") ?: ""); put("input", JsonObject(emptyMap())) } })
                ev("content_block_delta", buildJsonObject { put("type", "content_block_delta"); put("index", i); putJsonObject("delta") {
                    put("type", "input_json_delta"); put("partial_json", (o["input"] ?: JsonObject(emptyMap())).toString()) } })
            }
            ev("content_block_stop", buildJsonObject { put("type", "content_block_stop"); put("index", i) })
        }
        ev("message_delta", buildJsonObject { put("type", "message_delta"); putJsonObject("delta") { put("stop_reason", stop) }; putJsonObject("usage") { put("output_tokens", 0) } })
        ev("message_stop", buildJsonObject { put("type", "message_stop") })
    }

    private fun messageJson(content: String, calls: List<com.syntra.os.core.ToolCall>) = buildJsonObject {
        put("role", "assistant"); put("content", content)
        if (calls.isNotEmpty()) putJsonArray("tool_calls") { calls.forEachIndexed { i, t -> add(buildJsonObject {
            put("id", "call_$i"); put("type", "function")
            putJsonObject("function") { put("name", t.name); put("arguments", t.argsJson) }
        }) } }
    }

    /** Cloud pass-through: same request, provider's model id, streamed back byte-for-byte. */
    private fun forward(out: OutputStream, base: String, apiKey: String, model: String, req: JsonObject) {
        val body = JsonObject(req + ("model" to JsonPrimitive(model))).toString()
        val r = Request.Builder().url(base.trimEnd('/') + "/chat/completions").header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(r).execute().use { resp ->
            out.write("HTTP/1.1 ${resp.code} OK\r\nContent-Type: ${resp.header("Content-Type") ?: "application/json"}\r\nConnection: close\r\n\r\n".toByteArray())
            resp.body?.byteStream()?.copyTo(out)
            out.flush()
        }
    }
}
