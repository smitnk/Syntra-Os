package com.syntra.os.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun tool(name: String, desc: String, required: List<String>, props: Map<String, String>): JsonObject =
    buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", desc)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    props.forEach { (k, d) ->
                        putJsonObject(k) {
                            put("type", if (k == "arguments" || k == "body") "object" else "string")
                            put("description", d)
                        }
                    }
                }
                putJsonArray("required") { required.forEach { add(it) } }
            }
        }
    }

/** Tools that change the outside world. Each call needs a human tap first. */
val SENSITIVE_TOOLS = setOf("mcp_call")
fun isSensitive(name: String, args: JsonObject): Boolean =
    name in SENSITIVE_TOOLS || (name == "github" && (args["method"]?.toString()?.trim('"')?.uppercase() ?: "GET") != "GET")

val TOOLS: JsonArray = buildJsonArray {
    add(tool("memory_search", "Search the shared knowledge graph for entities, projects, people or story facts.",
        listOf("query"), mapOf("query" to "Words to look for")))
    add(tool("memory_remember", "Store or update an entity in the shared graph, optionally linking it to another entity.",
        listOf("label"), mapOf(
            "label" to "Entity name", "type" to "person|project|story|task|concept|place|tool",
            "notes" to "Facts to remember", "related_to" to "Label of an existing entity to link", "relation" to "Relation name, e.g. works_on")))
    add(tool("memory_link", "Link two existing entities.", listOf("from", "to", "relation"),
        mapOf("from" to "Label", "to" to "Label", "relation" to "Relation name")))
    add(tool("web_fetch", "Fetch a web page and return its title, main text and links (for research).",
        listOf("url"), mapOf("url" to "http(s) URL")))
    add(tool("github", "Call the GitHub REST API. GET is free; other methods ask the user first.",
        listOf("path"), mapOf("method" to "GET|POST|PATCH|PUT|DELETE", "path" to "e.g. /repos/owner/repo/issues", "body" to "JSON body")))
    add(tool("ask_specialist", "Ask another model in the team for specialist analysis. Roles: TEXT, VISION, AUDIO, OMNI, DECISION.",
        listOf("role", "question"), mapOf("role" to "Model role", "question" to "Self-contained question with needed context")))
    add(tool("mcp_list_tools", "List tools on a connected MCP server.", listOf("server"), mapOf("server" to "Server name")))
    add(tool("mcp_call", "Call a tool on a connected MCP server.", listOf("server", "tool"),
        mapOf("server" to "Server name", "tool" to "Tool name", "arguments" to "Tool arguments object")))
}
