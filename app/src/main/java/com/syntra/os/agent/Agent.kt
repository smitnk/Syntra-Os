package com.syntra.os.agent

import com.syntra.os.core.ChatClient
import com.syntra.os.core.ModelRole
import com.syntra.os.core.Settings
import com.syntra.os.core.Skill
import com.syntra.os.core.Step
import com.syntra.os.core.StepKind
import com.syntra.os.core.httpCall
import com.syntra.os.core.json
import com.syntra.os.core.message
import com.syntra.os.core.str
import com.syntra.os.core.toolResult
import com.syntra.os.mcp.McpClient
import com.syntra.os.memory.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import kotlin.coroutines.coroutineContext

/**
 * One agent run: the primary model plans and calls tools; it can pull other models
 * in via ask_specialist. All models share the same graph memory and task log.
 */
class Agent(
    private val settings: Settings,
    private val skills: List<Skill>,
    private val graph: () -> Graph,
    private val setGraph: (Graph) -> Unit,
    private val log: (Step) -> Unit,
    private val approve: suspend (String) -> Boolean,
) {
    private val mcp = settings.mcpServers.associate { it.name to McpClient(it) }
    private val transcript = StringBuilder() // shared task context handed to specialists

    suspend fun run(goal: String, history: List<JsonObject>, maxSteps: Int = 12): String {
        val primary = settings.slot(settings.primaryRole)
        val provider = settings.provider(primary.providerId) ?: error("No provider set for ${settings.primaryRole.label} model")
        val messages = mutableListOf(message("system", systemPrompt())).apply { addAll(history.takeLast(12)); add(message("user", goal)) }
        transcript.appendLine("User goal: $goal")

        repeat(maxSteps) {
            coroutineContext.ensureActive()
            val reply = ChatClient.complete(provider, primary.model, messages, TOOLS)
            messages += reply.rawMessage
            if (reply.toolCalls.isEmpty()) return reply.content.ifBlank { "(no answer)" }
            if (reply.content.isNotBlank()) log(Step(StepKind.THOUGHT, primary.model, reply.content))
            for (call in reply.toolCalls) {
                coroutineContext.ensureActive()
                val args = runCatching { json.parseToJsonElement(call.argsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                log(Step(StepKind.TOOL_CALL, primary.model, "${call.name} $args"))
                val result = try {
                    if (isSensitive(call.name, args) && !approve("${call.name} $args")) "User denied this action."
                    else execute(call.name, args)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    "Error: ${e.message}"
                }.take(6000)
                log(Step(StepKind.TOOL_RESULT, call.name, result.take(1500)))
                transcript.appendLine("${call.name} -> ${result.take(400)}")
                messages += toolResult(call.id, result)
            }
        }
        return "Stopped after $maxSteps steps without a final answer."
    }

    private fun systemPrompt(): String = buildString {
        appendLine("You are Syntra, the core intelligence of Syntra OS, a JARVIS-like agentic environment on the user's Android phone.")
        appendLine("Be concise and act with tools. Save durable facts about the user, projects and stories with memory_remember.")
        appendLine("Use ask_specialist when another model in the team (vision, audio, omni, decision) fits a sub-question better.")
        appendLine("Connected MCP servers: ${settings.mcpServers.joinToString { it.name }.ifBlank { "none" }}.")
        skills.filter { it.enabled }.forEach { appendLine("\n## Skill: ${it.name}\n${it.prompt.take(3000)}") }
        val mem = graph().describe()
        if (mem.isNotBlank()) appendLine("\n## Shared memory\n$mem")
    }

    private suspend fun execute(name: String, a: JsonObject): String = when (name) {
        "memory_search" -> {
            val g = graph()
            g.search(a.str("query").orEmpty()).take(15).joinToString("\n") { n ->
                "${n.label} [${n.type}] ${n.notes.take(200)} | " + g.neighbors(n.id).joinToString { (e, o) -> "${e.relation} ${o.label}" }
            }.ifBlank { "No matches." }
        }
        "memory_remember" -> {
            val label = a.str("label")?.takeIf { it.isNotBlank() } ?: error("label required")
            var (g, n) = graph().upsert(label, a.str("type") ?: "concept", a.str("notes").orEmpty())
            a.str("related_to")?.let { other ->
                val (g2, o) = g.upsert(other, "concept", "")
                g = g2.link(n.id, o.id, a.str("relation") ?: "related_to")
            }
            setGraph(g)
            "Saved ${n.label}."
        }
        "memory_link" -> {
            val g = graph()
            val from = g.find(a.str("from").orEmpty()) ?: error("unknown entity ${a.str("from")}")
            val to = g.find(a.str("to").orEmpty()) ?: error("unknown entity ${a.str("to")}")
            setGraph(g.link(from.id, to.id, a.str("relation") ?: "related_to"))
            "Linked."
        }
        "web_fetch" -> webFetch(a.str("url").orEmpty())
        "github" -> github(a)
        "ask_specialist" -> askSpecialist(a.str("role").orEmpty(), a.str("question").orEmpty())
        "mcp_list_tools" -> (mcp[a.str("server")] ?: error("no MCP server ${a.str("server")}")).listTools().toString()
        "mcp_call" -> {
            val client = mcp[a.str("server")] ?: error("no MCP server ${a.str("server")}")
            client.callTool(a.str("tool").orEmpty(), a["arguments"] as? JsonObject ?: JsonObject(emptyMap())).toString()
        }
        else -> "Unknown tool $name"
    }

    private suspend fun webFetch(url: String): String = withContext(Dispatchers.IO) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) URLs" }
        val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android) SyntraOS").timeout(20_000).get()
        doc.select("script,style,nav,footer,noscript").remove()
        val links = doc.select("a[href]").take(20).joinToString("\n") { "${it.text().take(60)} -> ${it.absUrl("href")}" }
        "Title: ${doc.title()}\n\n${doc.body()?.text().orEmpty().take(5000)}\n\nLinks:\n$links"
    }

    private suspend fun github(a: JsonObject): String {
        val path = a.str("path").orEmpty()
        require(path.startsWith("/") && !path.startsWith("//")) { "path must start with /" }
        val method = (a.str("method") ?: "GET").uppercase()
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            if (settings.githubToken.isNotBlank()) put("Authorization", "Bearer ${settings.githubToken}")
        }
        val r = httpCall("https://api.github.com$path", method, a["body"], headers)
        return "HTTP ${r.code}\n${r.body}"
    }

    private suspend fun askSpecialist(roleName: String, question: String): String {
        val role = ModelRole.entries.firstOrNull { it.name.equals(roleName, true) } ?: error("unknown role $roleName")
        val slot = settings.slot(role)
        val provider = settings.provider(slot.providerId) ?: error("no provider for ${role.label}")
        val sys = "You are the ${role.label} specialist (${slot.model}) on the Syntra OS model team. " +
            "Answer the lead model precisely.\n\nShared memory:\n${graph().describe(25)}\n\nTask so far:\n${transcript.toString().takeLast(2500)}"
        log(Step(StepKind.THOUGHT, slot.model, "Consulted: $question"))
        return ChatClient.complete(provider, slot.model, listOf(message("system", sys), message("user", question)), null).content
    }
}
