package com.syntra.os.mcp

import com.syntra.os.core.McpServer
import com.syntra.os.core.httpCall
import com.syntra.os.core.json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Minimal MCP client over Streamable HTTP (JSON-RPC 2.0; handles JSON and SSE replies). */
class McpClient(private val server: McpServer) {
    private var sessionId: String? = null
    private var nextId = 1
    private var initialized = false

    private suspend fun rpc(method: String, params: JsonObject, notify: Boolean = false): JsonObject? {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            if (!notify) put("id", nextId++)
            put("method", method)
            put("params", params)
        }
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("MCP-Protocol-Version", "2025-06-18")
            sessionId?.let { put("Mcp-Session-Id", it) }
            if (server.token.isNotBlank()) put("Authorization", "Bearer ${server.token}")
        }
        val r = httpCall(server.url, "POST", body, headers)
        r.headers["mcp-session-id"]?.let { sessionId = it }
        if (notify) return null
        if (r.code !in 200..299) error("MCP ${server.name} HTTP ${r.code}: ${r.body.take(300)}")
        val payload = if (r.body.trimStart().startsWith("{")) r.body
        else r.body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            .lastOrNull { it.startsWith("{") } ?: error("MCP ${server.name}: empty response")
        val obj = json.parseToJsonElement(payload).jsonObject
        obj["error"]?.let { error("MCP ${server.name}: $it") }
        return obj["result"]?.jsonObject
    }

    private suspend fun ensureInit() {
        if (initialized) return
        rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "syntra-os"); put("version", "0.1.0") }
        })
        rpc("notifications/initialized", JsonObject(emptyMap()), notify = true)
        initialized = true
    }

    suspend fun listTools(): JsonElement {
        ensureInit()
        return rpc("tools/list", JsonObject(emptyMap()))?.get("tools") ?: JsonObject(emptyMap())
    }

    suspend fun callTool(name: String, args: JsonObject): JsonElement {
        ensureInit()
        return rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) }) ?: JsonObject(emptyMap())
    }
}
