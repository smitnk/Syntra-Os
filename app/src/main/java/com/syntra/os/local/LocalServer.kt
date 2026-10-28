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
        if (r.headers["authorization"]?.removePrefix("Bearer ")?.trim() != key) return err(out, 401, "invalid API key (use \$OPENAI_API_KEY from Syntra)")
        when {
            r.method == "GET" && r.path.endsWith("/models") -> respond(out, 200, models().toString())
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
