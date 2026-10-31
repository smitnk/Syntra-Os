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
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
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
suspend fun callModel(env: AgentEnv, role: ModelRole, messages: List<JsonObject>, tools: JsonArray?, media: String?, onText: ((String) -> Unit)?,
                      background: Boolean = false): ChatReply {
    val slot = env.settings.slot(role)
    val provider = env.settings.provider(slot.providerId) ?: error("No provider set for the ${role.label} model (Setup)")
    return if (provider.local) env.engines.complete(role, messages, tools, media, onText, background = background)
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
    private val os = AgenticOs(env.context)
    private var skill: Skill? = null
    private var code = false

    companion object {
        /** MCP tool lists, fetched once per app session (Zapier etc. expose dozens of actions). */
        private val mcpTools = java.util.concurrent.ConcurrentHashMap<String, List<JsonObject>>()
    }

    /** A role can answer if its model is downloaded (on-device) or its cloud provider is configured. */
    fun available(role: ModelRole): Boolean {
        if (role == ModelRole.DECISION) return false // d1 classifies (yes/no, labels); it can't write text
        val p = settings.provider(settings.slot(role).providerId) ?: return false
        return if (p.local) env.engines.installedRoles().contains(role) else p.apiKey.isNotBlank() || "127.0.0.1" in p.baseUrl
    }

    /** Outcome of the last [run]: whether every tool claim checked out, and the real files produced. */
    var lastReport = RunReport(true, emptyList(), emptyList()); private set
    private val checkpoints = CheckpointStore(File(env.context.filesDir, "tasks"))
    private val artifacts = mutableListOf<File>()
    private val failures = mutableListOf<String>()

    /**
     * Runs a task. With [taskId] the run is checkpointed after every step and can be continued with [resume]
     * after a crash, app kill or model swap.
     */
    suspend fun run(goal: String, history: List<JsonObject>, image: String? = null, skill: Skill? = null, code: Boolean = false,
                    maxSteps: Int = if (code) 30 else 14, taskId: String? = null, chatOnly: Boolean = false): String {
        media = image; this.skill = skill; this.code = code
        val lead = pickLead()
        // Route first: only the tool groups this request needs go into the prompt (much less to read on a phone).
        var routes = Router.keywordRoutes(goal)
        if (routes.isEmpty() && settings.useD1Router && env.engines.installedRoles().contains(ModelRole.DECISION)) runCatching {
            val p = env.engines.classify(ModelRole.DECISION, Router.d1Prompt(goal), Router.labels)
            routes = setOf(Router.fromLabel(Router.labels[p.indices.maxBy { p[it] }]))
            env.log(Step(StepKind.THOUGHT, "d1-Omni-2B", "Routed as ${routes.first().name.lowercase()}"))
        }
        if (routes.isEmpty()) routes = setOf(Route.WEB) // unknown requests: allow a quick web lookup, nothing more
        if (chatOnly) routes = setOf(Route.CHAT) // Ask mode: answer from the model and memory only (fastest)
        val names = Router.toolNames(routes, code)
        val tools = JsonArray(TOOLS.filter { (it as JsonObject)["function"]?.jsonObject?.str("name") in names } +
            (if (Route.AUTOMATION in routes) loadMcpTools() else emptyList()) +
            (if (chatOnly) emptyList() else com.syntra.os.ext.ExtRuntime.definitions()))
        val team = teamBriefing(goal, image)
        val messages = mutableListOf(message("system", systemPrompt())).apply {
            addAll(history.takeLast(10))
            // Memory goes with the newest user turn, so the system prompt (cached on-device) rarely changes.
            val g = env.graph()
            val mem = (g.search(goal).take(8) + g.nodes.takeLast(6)).distinctBy { it.id }
                .joinToString("\n") { "- ${it.label} [${it.type}] ${it.notes.take(120)}" }
            add(message("user", goal + team + if (mem.isNotBlank()) "\n\n[What you remember]\n$mem" else ""))
        }
        blackboard.appendLine("Goal: $goal")
        val cp = taskId?.let { TaskCheckpoint(it, goal, messages, 0, code, skill?.name, tools.mapNotNull { t -> (t as JsonObject)["function"]?.jsonObject?.str("name") }) }
        return loop(lead, goal, messages, tools, 0, maxSteps, cp)
    }

    /** Continues a checkpointed run from its last completed step. */
    suspend fun resume(c: TaskCheckpoint, maxSteps: Int = if (c.code) 30 else 14): String {
        code = c.code; skill = env.skills.firstOrNull { it.name == c.skillName }
        artifacts += c.artifacts.map(::File).filter { it.exists() }; failures += c.failures
        val tools = JsonArray(TOOLS.filter { (it as JsonObject)["function"]?.jsonObject?.str("name") in c.toolNames } +
            (if (c.toolNames.any { it.startsWith("mcp") }) loadMcpTools() else emptyList()) +
            com.syntra.os.ext.ExtRuntime.definitions().filter { it["function"]?.jsonObject?.str("name") in c.toolNames })
        env.log(Step(StepKind.THOUGHT, "harness", "Resuming at step ${c.step + 1}"))
        return loop(pickLead(), c.goal, c.messages.toMutableList(), tools, c.step, maxSteps, c)
    }

    private fun pickLead(): ModelRole =
        settings.primaryRole.takeIf { available(it) } ?: listOf(ModelRole.OMNI, ModelRole.TEXT, ModelRole.VISION).firstOrNull { available(it) }
            ?: error("No chat model downloaded yet — open the Models screen and install one.")

    private suspend fun loop(lead: ModelRole, goal: String, messages: MutableList<JsonObject>, tools: JsonArray,
                             start: Int, maxSteps: Int, cp0: TaskCheckpoint?): String {
        var cp = cp0
        val local = settings.provider(settings.slot(lead).providerId)?.local == true
        val budget = if (local) 3000 else 24000 // on-device context is 4096 tokens; leave room for the reply
        var reprompts = 0
        for (step in start until maxSteps) {
            coroutineContext.ensureActive()
            trimToBudget(messages, budget)
            val reply = try { callModel(env, lead, messages, tools, media, env.onStream) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // One retry for transient model failures (cloud timeouts, a model reload after low memory).
                env.log(Step(StepKind.ERROR, "harness", "Model error, retrying once: ${e.message}"))
                kotlinx.coroutines.delay(1500)
                callModel(env, lead, messages, tools, media, env.onStream)
            }
            messages += reply.rawMessage
            if (reply.toolCalls.isEmpty()) {
                val raw = reply.rawMessage.str("raw") ?: reply.content
                // The model tried to call a tool but the markup didn't parse: tell it the exact format, once or twice.
                if (reprompts < 2 && ("<tool" in raw || "\"arguments\"" in raw)) {
                    reprompts++
                    messages += message("user", "Your tool call couldn't be read. Reply with exactly " +
                        "<tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>, or answer in plain text.")
                    continue
                }
                val answer = reply.content.ifBlank { "(no answer)" }
                skill?.let { s -> os.saveOutput(s.name, goal, "# ${s.name}\n\n**Task:** $goal\n\n$answer\n").let { r ->
                    File(os.root, r.removePrefix("Saved ").trim()).takeIf { it.isFile }?.let { artifacts += it } } }
                lastReport = RunReport(failures.isEmpty(), artifacts.distinct(), failures.toList())
                cp?.let { checkpoints.delete(it.id) }
                return answer
            }
            if (reply.content.isNotBlank()) env.log(Step(StepKind.THOUGHT, settings.slot(lead).model, reply.content))
            val results = runCalls(reply.toolCalls)
            reply.toolCalls.zip(results).forEach { (c, r) -> messages += toolResult(c.id, r) }
            cp = cp?.copy(messages = messages.toList(), step = step + 1, artifacts = artifacts.map { it.path }, failures = failures.toList(),
                updated = System.currentTimeMillis())
            cp?.let(checkpoints::save)
        }
        lastReport = RunReport(false, artifacts.distinct(), failures + "step limit reached")
        return "Stopped after $maxSteps steps without a final answer. You can resume it from the Tasks tab."
    }

    /**
     * Team pre-step: the VISION (or OMNI) model looks at an attached image and briefs the lead model.
     * (The d1 decision model is a classifier, so it never plans or writes text.)
     */
    private suspend fun teamBriefing(goal: String, image: String?): String = coroutineScope {
        // Omni (and VL) read images themselves; only a text-only lead needs a vision teammate to describe it.
        val leadSees = env.engines.hasProjector(settings.primaryRole) && settings.provider(settings.slot(settings.primaryRole).providerId)?.local == true
        val vision = if (image != null && !leadSees) listOf(ModelRole.OMNI, ModelRole.VISION).firstOrNull { available(it) }?.let { r ->
            async { "\n\n[${r.label} teammate sees the image]\n" + runCatching {
                env.log(Step(StepKind.THOUGHT, settings.slot(r).model, "Looking at the image…"))
                callModel(env, r, listOf(message("user", "Describe this image in detail for a teammate who cannot see it. Include any text.")), null, image, null).content
            }.getOrElse { "(vision failed: ${it.message})" } }
        } else null
        vision?.await().orEmpty()
    }

    private suspend fun loadMcpTools(): List<JsonObject> = coroutineScope {
        settings.mcpServers.map { s -> async {
            mcpTools[s.name] ?: runCatching {
                kotlinx.coroutines.withTimeout(8000) { mcp.getValue(s.name).listTools() }
            }.getOrNull()?.let { list ->
                (list as? JsonArray).orEmpty().take(40).mapNotNull { t ->
                    val o = t as? JsonObject ?: return@mapNotNull null
                    val name = o.str("name") ?: return@mapNotNull null
                    buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", "mcp__${s.name}__$name".take(64))
                            put("description", "[${s.name}] " + (o.str("description") ?: name).take(200))
                            put("parameters", o["inputSchema"] ?: buildJsonObject { put("type", "object") })
                        }
                    }
                }.also { mcpTools[s.name] = it }
            }.orEmpty()
        } }.awaitAll().flatten()
    }

    /** Safe calls run in parallel (so several models can work at once); approval-gated calls run one by one. */
    private suspend fun runCalls(calls: List<ToolCall>): List<String> = coroutineScope {
        val parsed = calls.map { it to (runCatching { json.parseToJsonElement(it.argsJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())) }
        val gated = parsed.map { (c, a) -> isSensitive(c.name, a, settings.askPhoneActions, settings.askLinux) }
        val out = arrayOfNulls<String>(calls.size)
        parsed.indices.filter { !gated[it] }.map { i -> async { i to safeExec(parsed[i].first, parsed[i].second) } }
            .awaitAll().forEach { (i, r) -> out[i] = r }
        parsed.indices.filter { gated[it] }.forEach { i ->
            val (c, a) = parsed[i]
            out[i] = if (!env.approve("[${riskOf(c.name).label}] ${c.name} $a")) "User denied this action." else safeExec(c, a)
        }
        out.map { it.orEmpty() }
    }

    private suspend fun safeExec(c: ToolCall, a: JsonObject): String {
        env.log(Step(StepKind.TOOL_CALL, c.name, a.toString().take(300)))
        suspend fun attempt() = try { execute(c.name, a) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "Error: ${e.message}"
        }.take(6000)
        var r = attempt()
        if (r.startsWith("Error:") && c.name in RETRYABLE) { kotlinx.coroutines.delay(1500); r = attempt() }
        // Verify the claim against reality; failures are fed back so the model can fix them.
        val v = Verifier.check(c.name, a, r, workspace, os.root)
        synchronized(failures) {
            v.artifact?.let { artifacts += it }
            if (!v.ok && r != "User denied this action.") { failures += "${c.name}: ${v.note}"; r += "\n[verification failed: ${v.note}]" }
        }
        env.log(Step(StepKind.TOOL_RESULT, c.name, r.take(1500)))
        synchronized(blackboard) { blackboard.appendLine("${c.name} -> ${r.take(300)}") }
        return r
    }

    /** Kept short on purpose: every token here is read before the first word appears. */
    private fun systemPrompt(): String = buildString {
        appendLine("You are Syntra, the user's on-phone AI assistant (like JARVIS). Act with tools when a request needs facts or actions; " +
            "don't tell the user to do what a tool can do. For news or facts: web_search, then web_fetch the best result.")
        appendLine("Reply in short, plain sentences (they may be spoken). Never show JSON or tool markup.")
        if (code) appendLine("CODE MODE: you are a coding agent in Ubuntu (/root/projects). Loop: todo plan → write files with shell heredocs " +
            "or code_agent → run with shell → fix errors → repeat until it works. Then say what you built and how to run it.")
        com.syntra.os.device.Linux.installedTools(env.context).takeIf { it.isNotEmpty() }?.let { appendLine("Installed in Linux: ${it.joinToString()}.") }
        append(os.context())
        val skills = skill?.let { listOf(it) } ?: env.skills.filter { it.enabled }
        skills.forEach { appendLine("\n## Skill: ${it.name}\n${it.prompt.take(1500)}") }
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
            else if (settings.askLinux) Termux.run(env.context, a.str("command").orEmpty()) // approved above; Termux is outside the sandbox
            else error("Install Ubuntu in the Linux tab (Termux commands need approval, so turn on 'Ask before Linux commands')")
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
        "learn" -> os.learn(a.str("rule").orEmpty())
        "save_output" -> os.saveOutput(a.str("folder") ?: skill?.name ?: "general", a.str("title") ?: "output", a.str("content").orEmpty())
        "todo" -> "Plan noted:\n" + a.str("items").orEmpty()
        "scrape" -> scrape(a.str("url").orEmpty(), a.str("selector"))
        "http_request" -> {
            val url = a.str("url").orEmpty()
            require(url.startsWith("https://") || url.startsWith("http://")) { "http(s) URL required" }
            val r = httpCall(url, (a.str("method") ?: "GET").uppercase(), a["body"], mapOf("Content-Type" to "application/json"))
            "HTTP ${r.code}\n${r.body.take(4000)}"
        }
        "hermes_agent" -> {
            check(com.syntra.os.device.Linux.installed(env.context)) { "Install Ubuntu, then Hermes, in the Linux tab" }
            com.syntra.os.device.Linux.run(env.context, "command -v hermes >/dev/null || { echo 'Hermes not installed (Linux tab → Hermes Agent)'; exit 1; }; " +
                "hermes -q ${shQuote(a.str("task").orEmpty())} -Q", timeoutMs = 900_000)
        }
        "claude_code" -> {
            check(com.syntra.os.device.Linux.installed(env.context)) { "Install Ubuntu, then Claude Code, in the Linux tab" }
            val dir = "/root/projects/" + AgenticOs.slug(a.str("project") ?: "app").ifBlank { "app" }
            val model = a.str("model")?.takeIf { it.isNotBlank() } ?: "syntra"
            com.syntra.os.device.Linux.run(env.context, "mkdir -p $dir && cd $dir && command -v claude >/dev/null || " +
                "{ echo 'Claude Code not installed (Linux tab → Claude Code)'; exit 1; }; " +
                "MODEL=${shQuote(model)} claude-local -p ${shQuote(a.str("task").orEmpty())} --dangerously-skip-permissions 2>&1 | tail -80",
                timeoutMs = 1_800_000)
        }
        "code_agent" -> {
            check(com.syntra.os.device.Linux.installed(env.context)) { "Install Ubuntu in the Linux tab first" }
            val dir = "/root/projects/" + AgenticOs.slug(a.str("project") ?: "app").ifBlank { "app" }
            com.syntra.os.device.Linux.run(env.context, "mkdir -p $dir && cd $dir && (git init -q 2>/dev/null || true); " +
                "command -v aider >/dev/null || { echo 'Aider not installed (Linux tab → Aider). Use shell to write files instead.'; exit 1; }; " +
                "aider --model openai/syntra --yes-always --no-show-model-warnings --no-check-update --message ${shQuote(a.str("task").orEmpty())} 2>&1 | tail -60",
                timeoutMs = 1_200_000)
        }
        "github" -> github(a)
        "delegate" -> delegate(a.str("agent").orEmpty(), a.str("task").orEmpty())
        "mcp_list_tools" -> (mcp[a.str("server")] ?: error("no MCP server ${a.str("server")}")).listTools().toString()
        "mcp_call" -> {
            val client = mcp[a.str("server")] ?: error("no MCP server ${a.str("server")}")
            client.callTool(a.str("tool").orEmpty(), a["arguments"] as? JsonObject ?: JsonObject(emptyMap())).toString()
        }
        else -> if (name in com.syntra.os.ext.ExtRuntime.tools) {
            val (extId, t) = com.syntra.os.ext.ExtRuntime.tools.getValue(name)
            check(com.syntra.os.device.Linux.installed(env.context)) { "Extension tools run in Linux — install Ubuntu in Workspace → Terminal" }
            com.syntra.os.device.Linux.run(env.context, com.syntra.os.ext.ExtRuntime.command(extId, t, a.toString()), (t.timeoutSec + 15) * 1000L)
        } else if (name.startsWith("mcp__")) {
            val server = name.removePrefix("mcp__").substringBefore("__")
            val tool = name.removePrefix("mcp__${server}__")
            (mcp[server] ?: error("no MCP server $server")).callTool(tool, a).toString()
        } else "Unknown tool $name"
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

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Scrapling (installed in Ubuntu) for robust extraction; Jsoup with a CSS selector otherwise. */
    private suspend fun scrape(url: String, selector: String?): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "http(s) URL required" }
        val linux = com.syntra.os.device.Linux
        if (linux.installed(env.context)) {
            val py = "from scrapling.fetchers import Fetcher\nimport sys\np=Fetcher.get(sys.argv[1], stealthy_headers=True)\n" +
                "sel=sys.argv[2] if len(sys.argv)>2 else ''\n" +
                "items=p.css(sel) if sel else [p]\n" +
                "print('\\n'.join((i.get_all_text(separator=' ', strip=True) or '')[:1500] for i in items[:30]))"
            val r = linux.run(env.context, "python3 -c 'import scrapling' 2>/dev/null && python3 - ${shQuote(url)} ${shQuote(selector.orEmpty())} <<'PY'\n$py\nPY", 120_000)
            if (r.startsWith("exit 0")) return "Scrapling:\n" + r.substringAfter('\n').take(6000)
        }
        return withContext(Dispatchers.IO) {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android) SyntraOS").timeout(20_000).get()
            val els = if (selector.isNullOrBlank()) listOf(doc.body()) else doc.select(selector)
            els.take(30).joinToString("\n") { it.text().take(1500) }.ifBlank { "Nothing matched." }
        }
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
        val asked = ModelRole.entries.firstOrNull { it.name.equals(roleName, true) } ?: error("unknown role $roleName")
        // If that teammate isn't downloaded, the nearest available one answers instead of failing the task.
        val role = asked.takeIf { available(it) } ?: listOf(ModelRole.OMNI, ModelRole.TEXT, ModelRole.VISION)
            .firstOrNull { available(it) } ?: error("no teammate model is available — download one in Setup")
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
    val reply = callModel(env, env.settings.primaryRole, listOf(message("user", prompt)), null, null, null, background = true).content
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
