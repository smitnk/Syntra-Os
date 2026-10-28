package com.syntra.os.agent

import android.content.Context
import com.syntra.os.core.ChatClient
import com.syntra.os.core.ChatReply
import com.syntra.os.core.ModelRole
import com.syntra.os.core.Settings
import com.syntra.os.core.Skill
import com.syntra.os.core.Step
import com.syntra.os.core.StepKind
import com.syntra.os.core.ToolCall
import com.syntra.os.core.httpCall
import com.syntra.os.core.json
import com.syntra.os.core.message
import com.syntra.os.core.str
import com.syntra.os.core.toolResult
import com.syntra.os.device.Phone
import com.syntra.os.device.Termux
import com.syntra.os.local.LocalEngines
import com.syntra.os.mcp.McpClient
import com.syntra.os.memory.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.io.File
import kotlin.coroutines.coroutineContext

/** Everything an agent run needs from the app. */
class AgentEnv(
    val context: Context,
    val settings: Settings,
    val skills: List<Skill>,
    val engines: LocalEngines,
    val graph: () -> Graph,
    val setGraph: (Graph) -> Unit,
    val log: (Step) -> Unit,
    val approve: suspend (String) -> Boolean,
    val onStream: (String) -> Unit = {},
)

/** Calls the model bound to a role: on-device llama.cpp or any OpenAI-compatible provider. */
suspend fun callModel(env: AgentEnv, role: ModelRole, messages: List<JsonObject>, tools: JsonArray?, media: String?, onText: ((String) -> Unit)?): ChatReply {
    val slot = env.settings.slot(role)
    val provider = env.settings.provider(slot.providerId) ?: error("No provider set for the ${role.label} model (Setup)")
    return if (provider.local) env.engines.complete(role, messages, tools, media, onText)
    else ChatClient.complete(provider, slot.model, messages, tools)
}

/**
 * One agent run. The lead model plans and calls tools; teammates are consulted through
 * ask_specialist / consult_team and run in parallel on their own engines. All of them share
 * the graph memory and a running task blackboard.
 */
class Agent(private val env: AgentEnv) {
    private val settings = env.settings
    private val mcp = settings.mcpServers.associate { it.name to McpClient(it) }
    private val blackboard = StringBuilder()
    private var media: String? = null
    private val workspace = File(env.context.filesDir, "workspace").apply { mkdirs() }

    suspend fun run(goal: String, history: List<JsonObject>, image: String? = null, maxSteps: Int = 12): String {
        media = image
        val lead = settings.primaryRole
        val messages = mutableListOf(message("system", systemPrompt())).apply {
            addAll(history.takeLast(10))
            // Memory goes with the newest user turn, so the system prompt (cached on-device) never changes.
            val mem = env.graph().let { g -> (g.search(goal).take(8).map { it.label } + g.nodes.takeLast(8).map { it.label }).distinct() }
                .mapNotNull { env.graph().find(it) }.joinToString("\n") { "- ${it.label} [${it.type}] ${it.notes.take(120)}" }
            val note = if (image != null) "\n(An image is attached; the VISION teammate can see it.)" else ""
            add(message("user", goal + note + if (mem.isNotBlank()) "\n\n[What you remember]\n$mem" else ""))
        }
        blackboard.appendLine("Goal: $goal")
        repeat(maxSteps) {
            coroutineContext.ensureActive()
            val reply = callModel(env, lead, messages, TOOLS, media, env.onStream)
            messages += reply.rawMessage
            if (reply.toolCalls.isEmpty()) return reply.content.ifBlank { "(no answer)" }
            if (reply.content.isNotBlank()) env.log(Step(StepKind.THOUGHT, settings.slot(lead).model, reply.content))
            val results = runCalls(reply.toolCalls)
            reply.toolCalls.zip(results).forEach { (c, r) -> messages += toolResult(c.id, r) }
        }
        return "Stopped after $maxSteps steps without a final answer."
    }

    /** Safe calls run in parallel (so several models can work at once); approval-gated calls run one by one. */
    private suspend fun runCalls(calls: List<ToolCall>): List<String> = coroutineScope {
        val parsed = calls.map { it to (runCatching { json.parseToJsonElement(it.argsJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())) }
        val gated = parsed.map { (c, a) -> isSensitive(c.name, a, settings.askPhoneActions) }
        val out = arrayOfNulls<String>(calls.size)
        parsed.indices.filter { !gated[it] }.map { i -> async { i to safeExec(parsed[i].first, parsed[i].second) } }
            .awaitAll().forEach { (i, r) -> out[i] = r }
        parsed.indices.filter { gated[it] }.forEach { i ->
            val (c, a) = parsed[i]
            out[i] = if (!env.approve("${c.name} $a")) "User denied this action." else safeExec(c, a)
        }
        out.map { it.orEmpty() }
    }

    private suspend fun safeExec(c: ToolCall, a: JsonObject): String {
        env.log(Step(StepKind.TOOL_CALL, c.name, a.toString().take(300)))
        val r = try { execute(c.name, a) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "Error: ${e.message}"
        }.take(6000)
        env.log(Step(StepKind.TOOL_RESULT, c.name, r.take(1500)))
        synchronized(blackboard) { blackboard.appendLine("${c.name} -> ${r.take(300)}") }
        return r
    }

    private fun systemPrompt(): String = buildString {
        appendLine("You are Syntra, the AI core of Syntra OS on the user's Android phone — a capable, calm assistant like JARVIS.")
        appendLine("You can operate the phone (phone_screen, phone_tap, open_app…), read notifications, schedule background tasks, run shell commands, browse the web, and use files.")
        appendLine("Be an agent, not a chatbot: when a request needs information or an action, call tools yourself instead of telling the user to do it.")
        appendLine("Examples: news/current facts → web_search then web_fetch; 'open X' → open_app; 'what's on screen' → phone_screen; code/files → shell.")
        appendLine("You lead a team of on-device LFM models. Ask teammates with ask_specialist, or several at once with consult_team.")
        appendLine("Answer in plain, natural sentences — never JSON or code unless the user asks for code. Keep answers short; they are spoken aloud.")
        appendLine("To act, call a tool. Save important facts about the user and their projects with memory_remember.")
        appendLine("External agents available via delegate: ${settings.providers.filter { it.agentModel.isNotBlank() }.joinToString { it.id }}.")
        appendLine("MCP servers: ${settings.mcpServers.joinToString { it.name }.ifBlank { "none" }}.")
        env.skills.filter { it.enabled }.forEach { appendLine("\n## Skill: ${it.name}\n${it.prompt.take(2000)}") }
    }

    private suspend fun execute(name: String, a: JsonObject): String = when (name) {
        "memory_search" -> {
            val g = env.graph()
            g.search(a.str("query").orEmpty()).take(15).joinToString("\n") { n ->
                "${n.label} [${n.type}] ${n.notes.take(200)} | " + g.neighbors(n.id).joinToString { (e, o) -> "${e.relation} ${o.label}" }
            }.ifBlank { "No matches." }
        }
        "memory_remember" -> {
            val label = a.str("label")?.takeIf { it.isNotBlank() } ?: error("label required")
            var (g, n) = env.graph().upsert(label, a.str("type") ?: "concept", a.str("notes").orEmpty())
            a.str("related_to")?.takeIf { it.isNotBlank() }?.let { other ->
                val (g2, o) = g.upsert(other, "concept", "")
                g = g2.link(n.id, o.id, a.str("relation") ?: "related_to")
            }
            env.setGraph(g)
            "Saved ${n.label}."
        }
        "memory_link" -> {
            val g = env.graph()
            val from = g.find(a.str("from").orEmpty()) ?: error("unknown entity ${a.str("from")}")
            val to = g.find(a.str("to").orEmpty()) ?: error("unknown entity ${a.str("to")}")
            env.setGraph(g.link(from.id, to.id, a.str("relation") ?: "related_to"))
            "Linked."
        }
        "ask_specialist" -> askSpecialist(a.str("role").orEmpty(), a.str("question").orEmpty())
        "consult_team" -> coroutineScope {
            a.str("roles").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
                .map { r -> async { "[$r] " + runCatching { askSpecialist(r, a.str("question").orEmpty()) }.getOrElse { "error: ${it.message}" } } }
                .awaitAll().joinToString("\n\n")
        }
        "web_fetch" -> webFetch(a.str("url").orEmpty())
        "phone_screen" -> Phone.screen()
        "phone_tap" -> Phone.tap(a.str("target").orEmpty())
        "phone_type" -> Phone.type(a.str("text").orEmpty(), a.str("target"))
        "phone_swipe" -> Phone.swipe(a.str("direction") ?: "up")
        "phone_action" -> Phone.action(a.str("action").orEmpty())
        "open_app" -> withContext(Dispatchers.Main) { Phone.openApp(env.context, a.str("name").orEmpty()) }
        "list_apps" -> Phone.listApps(env.context)
        "shell" -> if (com.syntra.os.device.Linux.installed(env.context)) com.syntra.os.device.Linux.run(env.context, a.str("command").orEmpty())
            else Termux.run(env.context, a.str("command").orEmpty())
        "web_search" -> webSearch(a.str("query").orEmpty())
        "file_list" -> safeFile(a.str("path") ?: ".").listFiles().orEmpty()
            .joinToString("\n") { (if (it.isDirectory) "dir  " else "file ") + it.name + " " + it.length() }.ifBlank { "(empty)" }
        "file_read" -> safeFile(a.str("path").orEmpty()).readText().take(6000)
        "file_write" -> safeFile(a.str("path").orEmpty()).let { it.parentFile?.mkdirs(); it.writeText(a.str("content").orEmpty()); "Wrote ${it.name}." }
        "read_notifications" -> com.syntra.os.device.SyntraNotifications.read()
        "schedule_task" -> Scheduler.schedule(env.context, a.str("goal").orEmpty().ifBlank { error("goal required") },
            a.str("in_minutes")?.toLongOrNull() ?: 0, a.str("repeat_hours")?.toLongOrNull())
        "list_schedules" -> withContext(Dispatchers.IO) { Scheduler.list(env.context) }
            .joinToString("\n") { (id, g) -> "$id: $g" }.ifBlank { "Nothing scheduled." }
        "cancel_schedule" -> Scheduler.cancel(env.context, a.str("id").orEmpty())
        "github" -> github(a)
        "delegate" -> delegate(a.str("agent").orEmpty(), a.str("task").orEmpty())
        "mcp_list_tools" -> (mcp[a.str("server")] ?: error("no MCP server ${a.str("server")}")).listTools().toString()
        "mcp_call" -> {
            val client = mcp[a.str("server")] ?: error("no MCP server ${a.str("server")}")
            client.callTool(a.str("tool").orEmpty(), a["arguments"] as? JsonObject ?: JsonObject(emptyMap())).toString()
        }
        else -> "Unknown tool $name"
    }

    /** Workspace paths can't escape the sandbox folder. */
    private fun safeFile(path: String): File {
        val f = File(workspace, path).canonicalFile
        require(f.path.startsWith(workspace.canonicalPath)) { "path outside workspace" }
        return f
    }

    private suspend fun webSearch(q: String): String = withContext(Dispatchers.IO) {
        require(q.isNotBlank()) { "query required" }
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/").data("q", q).userAgent("Mozilla/5.0 (Linux; Android) SyntraOS")
            .timeout(20_000).post()
        doc.select(".result").take(8).mapIndexed { i, r ->
            val a = r.selectFirst("a.result__a")
            var href = a?.attr("href").orEmpty()
            // DuckDuckGo wraps links as //duckduckgo.com/l/?uddg=<encoded url>
            Regex("uddg=([^&]+)").find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            "${i + 1}. ${a?.text().orEmpty()}\n   $href\n   ${r.selectFirst(".result__snippet")?.text().orEmpty().take(220)}"
        }.joinToString("\n").ifBlank { "No results." }
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
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            if (settings.githubToken.isNotBlank()) put("Authorization", "Bearer ${settings.githubToken}")
        }
        val r = httpCall("https://api.github.com$path", (a.str("method") ?: "GET").uppercase(), a["body"], headers)
        return "HTTP ${r.code}\n${r.body}"
    }

    private suspend fun delegate(agent: String, task: String): String {
        val p = settings.providers.firstOrNull { it.id.equals(agent, true) && it.agentModel.isNotBlank() }
            ?: error("no delegate agent '$agent' (configure OpenClaw/Hermes in Setup)")
        env.log(Step(StepKind.THOUGHT, p.name, "Delegated: $task"))
        return ChatClient.complete(p, p.agentModel, listOf(message("user", task)), null).content
    }

    private suspend fun askSpecialist(roleName: String, question: String): String {
        val role = ModelRole.entries.firstOrNull { it.name.equals(roleName, true) } ?: error("unknown role $roleName")
        val slot = settings.slot(role)
        val board = synchronized(blackboard) { blackboard.toString().takeLast(2000) }
        val sys = "You are the ${role.label} specialist (${slot.model}) on the Syntra OS model team. Answer the lead model precisely and briefly." +
            "\n\nShared memory:\n${env.graph().describe(20)}\n\nTeam blackboard:\n$board"
        env.log(Step(StepKind.THOUGHT, slot.model, "Consulted: $question"))
        val needsMedia = role == ModelRole.VISION || role == ModelRole.OMNI
        return callModel(env, role, listOf(message("system", sys), message("user", question)), null, media?.takeIf { needsMedia }, null).content
    }
}

/** After each exchange the lead model decides what is worth remembering — the user never has to. */
/**
 * After each exchange: the chat itself becomes a "conversation" node linked to the user, and the lead model
 * extracts people/projects/topics/facts that get linked to that conversation. The user never adds anything by hand.
 */
suspend fun extractMemories(env: AgentEnv, chatTitle: String, userText: String, answer: String) {
    var g = env.graph()
    val (g0, me) = g.upsert("Me", "person", "")
    val (g1, conv) = g0.upsert("Chat: ${chatTitle.take(48)}", "conversation", "You: ${userText.take(160)}\nSyntra: ${answer.take(200)}")
    g = g1.link(me.id, conv.id, "talked_about")
    env.setGraph(g) // saved even if extraction below fails

    val prompt = """List the people, projects, places, topics, apps and facts in this exchange as a knowledge graph.
Always include at least the main topic. Reply with ONLY JSON:
{"entities":[{"label":"","type":"person|project|story|task|topic|place|app|preference","notes":""}],"relations":[{"from":"","to":"","relation":""}]}
Use "Me" for the user.

User: $userText
Assistant: ${answer.take(800)}"""
    val reply = callModel(env, env.settings.primaryRole, listOf(message("user", prompt)), null, null, null).content
    val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return
    val o = runCatching { json.parseToJsonElement(com.syntra.os.local.LfmPrompt.lenient(reply.substring(start, end + 1))).jsonObject }.getOrNull() ?: return
    g = env.graph()
    (o["entities"] as? JsonArray).orEmpty().forEach { e ->
        val m = e as? JsonObject ?: return@forEach
        val label = m.str("label")?.trim().orEmpty()
        if (label.isBlank() || label.length > 60) return@forEach
        val (g2, n) = g.upsert(label, m.str("type") ?: "topic", m.str("notes").orEmpty())
        g = if (n.id == conv.id || n.id == me.id) g2 else g2.link(conv.id, n.id, "mentions")
    }
    (o["relations"] as? JsonArray).orEmpty().forEach { r ->
        val m = r as? JsonObject ?: return@forEach
        val from = m.str("from")?.trim().orEmpty(); val to = m.str("to")?.trim().orEmpty()
        if (from.isBlank() || to.isBlank()) return@forEach
        val (g3, x) = g.upsert(from, "topic", "")
        val (g4, y) = g3.upsert(to, "topic", "")
        g = g4.link(x.id, y.id, m.str("relation")?.ifBlank { null } ?: "related_to")
    }
    env.setGraph(g)
}
