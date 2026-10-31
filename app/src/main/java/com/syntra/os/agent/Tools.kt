package com.syntra.os.agent

import com.syntra.os.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val OBJECT_PARAMS = setOf("arguments", "body")

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
                            put("type", if (k in OBJECT_PARAMS) "object" else "string")
                            put("description", d)
                        }
                    }
                }
                putJsonArray("required") { required.forEach { add(it) } }
            }
        }
    }

val PHONE_TOOLS = setOf("phone_tap", "phone_type", "phone_swipe", "phone_action", "open_app")
/** Always need a human tap: they reach outside the phone or run arbitrary code. */
val ALWAYS_ASK = setOf("mcp_call", "delegate")

/** Commands inside the app's own Ubuntu sandbox; the user can let these run without asking. */
val LINUX_TOOLS = setOf("shell", "code_agent", "hermes_agent", "claude_code")

fun isSensitive(name: String, args: JsonObject, askPhone: Boolean, askLinux: Boolean = true): Boolean =
    name in ALWAYS_ASK || (askLinux && (name in LINUX_TOOLS || name in com.syntra.os.ext.ExtRuntime.tools)) ||
        (askPhone && name in PHONE_TOOLS) || name.startsWith("mcp__") ||
        ((name == "github" || name == "http_request") && (args.str("method") ?: "GET").uppercase() != "GET")

val TOOLS: JsonArray = buildJsonArray {
    add(tool("knowledge_search", "Search the user's documents (knowledge base). Returns numbered passages with source and page; cite them as [n].",
        listOf("query"), mapOf("query" to "What to look for, in natural words")))
    add(tool("knowledge_add", "Add a workspace file (pdf, docx, md, txt, html…) or a web page URL to the knowledge base.",
        listOf("source"), mapOf("source" to "Workspace path or http(s) URL")))
    add(tool("memory_search", "Search the shared knowledge graph for entities, projects, people or story facts.",
        listOf("query"), mapOf("query" to "Words to look for")))
    add(tool("memory_remember", "Store or update an entity in the shared graph, optionally linking it to another entity.",
        listOf("label"), mapOf(
            "label" to "Entity name", "type" to "person|project|story|task|concept|place|tool",
            "notes" to "Facts to remember", "related_to" to "Label of an existing entity to link", "relation" to "Relation name, e.g. works_on")))
    add(tool("memory_link", "Link two existing entities.", listOf("from", "to", "relation"),
        mapOf("from" to "Label", "to" to "Label", "relation" to "Relation name")))
    add(tool("ask_specialist", "Ask one teammate model. Roles: TEXT, VISION (sees the attached image), OMNI.",
        listOf("role", "question"), mapOf("role" to "Model role", "question" to "Self-contained question")))
    add(tool("consult_team", "Ask several teammate models the same question at once; they answer in parallel.",
        listOf("roles", "question"), mapOf("roles" to "Comma-separated roles, e.g. VISION,DECISION", "question" to "Self-contained question")))
    add(tool("web_search", "Search the web (DuckDuckGo). Use for news, facts, anything current. Then web_fetch the best result.",
        listOf("query"), mapOf("query" to "Search words")))
    add(tool("web_fetch", "Fetch a web page and return its title, main text and links.", listOf("url"), mapOf("url" to "http(s) URL")))
    add(tool("phone_screen", "Read what is on the phone screen right now as a numbered list.", emptyList(), emptyMap()))
    add(tool("phone_tap", "Tap a screen item by its [number] or visible text.", listOf("target"), mapOf("target" to "Item number or text")))
    add(tool("phone_type", "Type text into a text field (focused one by default).", listOf("text"), mapOf("text" to "Text", "target" to "Optional field number or label")))
    add(tool("phone_swipe", "Swipe the screen.", listOf("direction"), mapOf("direction" to "up|down|left|right")))
    add(tool("phone_action", "Press a system button.", listOf("action"), mapOf("action" to "back|home|recents|notifications|quick_settings")))
    add(tool("open_app", "Open an installed app by name.", listOf("name"), mapOf("name" to "App name")))
    add(tool("list_apps", "List installed apps.", emptyList(), emptyMap()))
    add(tool("shell", "Run a bash command in the built-in Ubuntu Linux (apt, python, git, curl…).", listOf("command"), mapOf("command" to "Shell command")))
    add(tool("file_list", "List files in Syntra's workspace folder.", emptyList(), mapOf("path" to "Sub-folder, optional")))
    add(tool("file_read", "Read a text file from the workspace.", listOf("path"), mapOf("path" to "Relative path")))
    add(tool("file_write", "Write a text file in the workspace.", listOf("path", "content"), mapOf("path" to "Relative path", "content" to "File content")))
    add(tool("github", "Call the GitHub REST API. GET is free; other methods ask the user first.",
        listOf("path"), mapOf("method" to "GET|POST|PATCH|PUT|DELETE", "path" to "e.g. /repos/owner/repo/issues", "body" to "JSON body")))
    add(tool("delegate", "Hand a whole task to an external agent (OpenClaw or Hermes) and get its result.",
        listOf("agent", "task"), mapOf("agent" to "openclaw|hermes", "task" to "Complete task description")))
    add(tool("read_notifications", "Read the phone's current notifications.", emptyList(), emptyMap()))
    add(tool("schedule_task", "Run a goal later in the background (and optionally repeat). The result arrives as a notification.",
        listOf("goal", "in_minutes"), mapOf("goal" to "What to do", "in_minutes" to "Delay in minutes", "repeat_hours" to "Repeat every N hours, optional")))
    add(tool("list_schedules", "List scheduled background tasks.", emptyList(), emptyMap()))
    add(tool("cancel_schedule", "Cancel a scheduled task by id.", listOf("id"), mapOf("id" to "Job id from list_schedules")))
    add(tool("scrape", "Extract text from a web page with Scrapling (CSS selector optional), for research and structured data.",
        listOf("url"), mapOf("url" to "http(s) URL", "selector" to "CSS selector, e.g. h2 a or .price")))
    add(tool("http_request", "Call a URL/webhook (e.g. a Zapier Catch Hook). Non-GET asks the user first.",
        listOf("url"), mapOf("method" to "GET|POST|PUT", "url" to "URL", "body" to "JSON body")))
    add(tool("learn", "Remember a rule from the user's correction or preference (agentic-os memory/learnings.md).",
        listOf("rule"), mapOf("rule" to "The rule, e.g. Always reply in Hindi when I write in Hindi")))
    add(tool("save_output", "Save a finished result to agentic-os/outputs/<folder>.", listOf("title", "content"),
        mapOf("folder" to "Folder, e.g. linkedin", "title" to "Short title", "content" to "Markdown content")))
    add(tool("todo", "Write or update your step-by-step plan for a multi-step task.", listOf("items"), mapOf("items" to "Numbered steps, mark done with [x]")))
    add(tool("code_agent", "Multi-file coding in Ubuntu with Aider: creates/edits files in /root/projects/<project>.",
        listOf("task"), mapOf("project" to "Project folder name", "task" to "What to build or change")))
    add(tool("claude_code", "Run Claude Code (installed in Ubuntu) headless on a coding task in /root/projects/<project>.",
        listOf("task"), mapOf("project" to "Project folder name", "task" to "What to build or change", "model" to "syntra (on-device) or provider/model, e.g. deepseek/deepseek-chat")))
    add(tool("hermes_agent", "Hand a long, multi-step task to Hermes Agent running in Ubuntu (uses Syntra's models).",
        listOf("task"), mapOf("task" to "Complete task description")))
    add(tool("mcp_list_tools", "List tools on a connected MCP server.", listOf("server"), mapOf("server" to "Server name")))
    add(tool("mcp_call", "Call a tool on a connected MCP server.", listOf("server", "tool"),
        mapOf("server" to "Server name", "tool" to "Tool name", "arguments" to "Tool arguments object")))
}
