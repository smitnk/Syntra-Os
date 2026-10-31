package com.syntra.os

import android.app.Application
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syntra.os.agent.Agent
import com.syntra.os.agent.AgentEnv
import com.syntra.os.agent.Skills
import com.syntra.os.agent.extractMemories
import com.syntra.os.core.DEFAULT_PROVIDERS
import com.syntra.os.core.ModelRole
import com.syntra.os.core.Settings
import com.syntra.os.core.Skill
import com.syntra.os.core.Step
import com.syntra.os.core.StepKind
import com.syntra.os.core.Store
import com.syntra.os.core.TaskRun
import com.syntra.os.core.TaskStatus
import com.syntra.os.core.message
import com.syntra.os.local.LocalEngines
import com.syntra.os.local.ModelManager
import com.syntra.os.memory.Graph
import com.syntra.os.voice.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app.filesDir)
    val models = ModelManager(app.filesDir)
    val engines = LocalEngines(models, app) { if (settings.useGpu) 99 else 0 }

    var settings by mutableStateOf(migrate(store.load("settings", Settings.serializer(), Settings()))); private set
    var graph by mutableStateOf(store.load("graph", Graph.serializer(), Graph())); private set
    var skills by mutableStateOf(store.load("skills", ListSerializer(Skill.serializer()), Skills.builtIn)); private set
    var tasks by mutableStateOf(store.load("tasks", ListSerializer(TaskRun.serializer()), emptyList())); private set
    var chat by mutableStateOf(listOf<Step>()); private set
    var sessions by mutableStateOf(store.load("chats", ListSerializer(com.syntra.os.core.ChatSession.serializer()), emptyList())); private set
    private var chatId = System.currentTimeMillis().toString()
    val chatTitle get() = chat.firstOrNull { it.kind == StepKind.USER }?.text?.take(60) ?: "New chat"

    /** Saves the open chat into the history list (newest first). */
    private fun persistChat() {
        if (chat.none { it.kind == StepKind.USER }) return
        val s = com.syntra.os.core.ChatSession(chatId, chatTitle, chat.takeLast(200))
        sessions = (listOf(s) + sessions.filterNot { it.id == chatId }).take(100)
        store.save("chats", ListSerializer(com.syntra.os.core.ChatSession.serializer()), sessions)
    }

    fun newChat() { if (busy) return; persistChat(); chat = emptyList(); chatId = System.currentTimeMillis().toString() }
    fun openChat(id: String) {
        if (busy) return
        persistChat()
        sessions.firstOrNull { it.id == id }?.let { chat = it.steps; chatId = it.id }
    }
    fun deleteChat(id: String) {
        sessions = sessions.filterNot { it.id == id }
        store.save("chats", ListSerializer(com.syntra.os.core.ChatSession.serializer()), sessions)
        if (id == chatId) { chat = emptyList(); chatId = System.currentTimeMillis().toString() }
    }
    var pendingApproval by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    /** Task being run right now (its steps feed the live run card). */
    var currentTaskId by mutableStateOf<String?>(null); private set
    /** Composer mode: "ask" (answer only), "agent" (tools as needed), "code" (coding harness). */
    var mode by mutableStateOf("agent")
    var status by mutableStateOf(""); private set
    var streaming by mutableStateOf(""); private set
    var activeRoles by mutableStateOf(emptySet<ModelRole>()); private set
    var attachment by mutableStateOf<String?>(null)
    var voiceState by mutableStateOf(VoiceState.OFF)
    var heard by mutableStateOf("")
    var micLevel by mutableStateOf(0f)
    /** Set after an answer arrives so the activity can speak it. */
    var lastAnswer by mutableStateOf<String?>(null)
    val downloads = mutableStateMapOf<ModelRole, String>()
    var installedTick by mutableStateOf(0); private set

    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null

    val server = com.syntra.os.local.LocalServer(engines, { settings }, settings.localApiKey)
    val agenticOs = com.syntra.os.agent.AgenticOs(app)

    init {
        store.save("settings", Settings.serializer(), settings) // persists a freshly generated local API key
        applyOverrides()
        // A task still RUNNING at startup means the process died mid-run; it can be resumed from its checkpoint.
        tasks = tasks.map { if (it.status == TaskStatus.RUNNING || it.status == TaskStatus.WAITING_APPROVAL) it.copy(status = TaskStatus.INTERRUPTED) else it }
        server.start()
        runCatching { com.syntra.os.device.Linux.configure(app, settings.localApiKey) }
        java.io.File(app.filesDir, "crash.txt").takeIf { it.exists() }?.let { f ->
            chat = listOf(Step(StepKind.ERROR, "system", "Syntra crashed last time:\n" + f.readText().lines().take(6).joinToString("\n")))
            f.delete()
        }
        // Light up the model-team row while engines generate.
        viewModelScope.launch { while (isActive) { activeRoles = engines.active; delay(150) } }
    }

    /** Older installs lack the on-device / OpenClaw / Hermes providers. */
    private fun migrate(s0: Settings): Settings {
        var s = if (s0.primaryRole == ModelRole.DECISION) s0.copy(primaryRole = ModelRole.OMNI) else s0 // d1 can't chat
        // Omni is the all-rounder (text + images + audio): make it the lead once, as soon as it's downloaded.
        if (!s.omniDefaultApplied && models.installed(ModelRole.OMNI) != null) s = s.copy(primaryRole = ModelRole.OMNI, omniDefaultApplied = true)
        val missing = DEFAULT_PROVIDERS.filter { d -> s.providers.none { it.id == d.id } }
        return if (missing.isEmpty()) s else s.copy(providers = missing + s.providers)
    }

    private fun env(log: (Step) -> Unit = {}) = AgentEnv(getApplication(), settings, skills, engines, { graph }, ::updateGraph,
        log = log, approve = ::askApproval, onStream = { streaming = it }, dotNow = { id -> dots.firstOrNull { it.id == id } })

    fun send(text: String) {
        if (text.isBlank() || busy) return
        val history = chat.mapNotNull {
            when (it.kind) { StepKind.USER -> message("user", it.text); StepKind.ANSWER -> message("assistant", it.text); else -> null }
        }
        val image = attachment
        attachment = null
        chat = chat + Step(StepKind.USER, if (image != null) "you · image" else "you", text)
        val task = TaskRun(id = System.currentTimeMillis().toString(), goal = text)
        currentTaskId = task.id
        tasks = (listOf(task) + tasks).take(30)
        busy = true
        streaming = ""
        job = viewModelScope.launch {
            val e = env { step -> status = "${step.who}: ${step.text}".take(90); editTask(task.id) { it.copy(steps = it.steps + step) } }
            try {
                val mention = com.syntra.os.agent.Dot.mention(text, dots)
                if (text.startsWith("@") && mention == null) error("No Dot called ${text.substringBefore(' ')} — see Dots in the menu.")
                if (mention != null) {
                    val (dot, goal) = mention
                    val agent = Agent(e)
                    val answer = agent.run(goal.ifBlank { "Introduce yourself in one sentence." }, history, image, taskId = task.id, dot = dot)
                    val rep = agent.lastReport
                    val done = Step(StepKind.ANSWER, dot.name, answer, files = rep.artifacts.map { it.path }, verified = rep.verified, notes = rep.failures, taskId = task.id)
                    chat = chat + done
                    editTask(task.id) { it.copy(status = TaskStatus.DONE, steps = it.steps + done) }
                    lastAnswer = answer
                    return@launch
                }
                val slash = com.syntra.os.agent.AgenticOs.parseSlash(text, skills)
                val code = text.startsWith("/code") || mode == "code"
                val goal = if (slash != null && (slash.first != null || code)) slash.second.ifBlank { text } else text
                if (slash != null && slash.first == null && !code) error("No skill called ${text.substringBefore(' ')} — see the Skills tab (or use /code).")
                val agent = Agent(e)
                val answer = agent.run(goal, history, image, skill = slash?.first, code = code, taskId = task.id, chatOnly = mode == "ask" && slash == null)
                val rep = agent.lastReport
                val done = Step(StepKind.ANSWER, settings.mainModelId ?: settings.slot(settings.primaryRole).model, answer,
                    files = rep.artifacts.map { it.path }, verified = rep.verified, notes = rep.failures, taskId = task.id)
                chat = chat + done
                editTask(task.id) { it.copy(status = if (rep.failures.lastOrNull() == "step limit reached") TaskStatus.INTERRUPTED else TaskStatus.DONE,
                    steps = it.steps + done.copy(who = "syntra")) }
                lastAnswer = answer
                if (settings.autoMemory) { val title = chatTitle; launch {
                    runCatching { extractMemories(e, title, text, answer) }.onFailure { status = "Memory: ${it.message}" }
                } }
            } catch (ex: CancellationException) {
                editTask(task.id) { it.copy(status = TaskStatus.CANCELLED) }
                chat = chat + Step(StepKind.ERROR, "system", "Cancelled.")
            } catch (ex: Exception) {
                editTask(task.id) { it.copy(status = TaskStatus.FAILED, steps = it.steps + Step(StepKind.ERROR, "system", ex.message.orEmpty())) }
                chat = chat + Step(StepKind.ERROR, "system", ex.message ?: ex.toString())
                lastAnswer = ex.message?.take(140)
            } finally {
                busy = false; status = ""; streaming = ""; pendingApproval = null
                persistChat()
                store.save("tasks", ListSerializer(TaskRun.serializer()), tasks)
            }
        }
    }

    // ---- Dots: named specialist agents (ported from OpenDots) ----
    var dots by mutableStateOf(store.load("dots", ListSerializer(com.syntra.os.agent.Dot.serializer()), com.syntra.os.agent.Dot.PRESETS)); private set

    fun saveDot(d: com.syntra.os.agent.Dot) {
        require(d.name.isNotBlank()) { "name required" }
        dots = dots.filter { it.id != d.id } + d
        store.save("dots", ListSerializer(com.syntra.os.agent.Dot.serializer()), dots)
    }

    fun deleteDot(id: String) {
        dots = dots.filter { it.id != id }
        store.save("dots", ListSerializer(com.syntra.os.agent.Dot.serializer()), dots)
    }

    // ---- Extensions (Phase 4) ----
    // ---- Knowledge base (RAG) ----
    var knowledgeDocs by mutableStateOf(emptyList<com.syntra.os.rag.Doc>()); private set
    var knowledgeStatus by mutableStateOf(""); private set
    var knowledgeBusy by mutableStateOf(false); private set
    var knowledgeHits by mutableStateOf(emptyList<com.syntra.os.rag.Hit>()); private set
    val embedderInstalled get() = models.embedder() != null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val st = com.syntra.os.rag.KnowledgeStore(java.io.File(app.filesDir, "knowledge"))
            com.syntra.os.rag.Knowledge.store = st
            models.embedder()?.let { com.syntra.os.rag.Knowledge.embedder = com.syntra.os.rag.LazyEmbedder(it, 4) }
            knowledgeDocs = st.docs
        }
    }

    private fun knowledgeJob(block: suspend () -> String) {
        if (knowledgeBusy) return
        knowledgeBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            knowledgeStatus = runCatching { block() }.getOrElse { "Error: ${it.message}" }
            knowledgeDocs = com.syntra.os.rag.Knowledge.store?.docs.orEmpty()
            knowledgeBusy = false
        }
    }

    fun importDocuments(uris: List<android.net.Uri>) = knowledgeJob {
        val st = com.syntra.os.rag.Knowledge.store ?: error("knowledge base not ready")
        val cr = getApplication<Application>().contentResolver
        uris.joinToString("\n") { uri ->
            val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "document"
            runCatching {
                require(com.syntra.os.rag.Extract.supported(name)) { "unsupported type" }
                knowledgeStatus = "Reading $name…"
                val pages = cr.openInputStream(uri)!!.use { com.syntra.os.rag.Extract.pages(getApplication(), name, it) }
                val d = st.add(name, uri.toString(), pages, com.syntra.os.rag.Knowledge.embedder) { i, n -> if (i % 10 == 0 || i == n) knowledgeStatus = "Embedding $name: $i/$n" }
                "$name: ${d.chunks} passages" + if (d.pages > 0) " from ${d.pages} pages" else ""
            }.getOrElse { "$name: ${it.message}" }
        }
    }

    fun removeDocument(id: String) = knowledgeJob { com.syntra.os.rag.Knowledge.store?.remove(id); "Removed." }

    fun searchKnowledge(q: String) = knowledgeJob {
        val t = System.currentTimeMillis()
        knowledgeHits = com.syntra.os.rag.Knowledge.search(q, 6)
        "${knowledgeHits.size} passages in ${System.currentTimeMillis() - t} ms" + if (com.syntra.os.rag.Knowledge.embedder == null) " · keyword search" else " · hybrid search"
    }

    fun downloadEmbedder() = knowledgeJob {
        val f = models.downloadEmbedder { knowledgeStatus = "Downloading $it" }
        val e = com.syntra.os.rag.LazyEmbedder(f, 4)
        com.syntra.os.rag.Knowledge.embedder = e
        com.syntra.os.rag.Knowledge.store?.embedMissing(e) { knowledgeStatus = "Embedding $it" }
        "Semantic search ready (${f.name})."
    }

    val extManager = com.syntra.os.ext.ExtensionManager(app.filesDir) { settings.githubToken }
    var extensions by mutableStateOf(store.load("extensions", ListSerializer(com.syntra.os.ext.InstalledExt.serializer()), emptyList())); private set
    var plan by mutableStateOf<com.syntra.os.ext.InstallPlan?>(null); private set
    var extStatus by mutableStateOf(""); private set
    var extBusy by mutableStateOf(false); private set
    val extUpdates = mutableStateMapOf<String, String>() // id → newer sha
    init { com.syntra.os.ext.ExtRuntime.tools = extensions.filter { it.enabled && it.toolsReady }
        .flatMap { e -> e.manifest.tools.map { t -> t.name to (e.manifest.id to t) } }.toMap() } // after `extensions` is initialised

    private fun saveExtensions(list: List<com.syntra.os.ext.InstalledExt>) {
        extensions = list
        store.save("extensions", ListSerializer(com.syntra.os.ext.InstalledExt.serializer()), list)
        com.syntra.os.ext.ExtRuntime.tools = list.filter { it.enabled && it.toolsReady }
            .flatMap { e -> e.manifest.tools.map { t -> t.name to (e.manifest.id to t) } }.toMap()
    }

    fun inspectExtension(input: String) {
        if (extBusy || input.isBlank()) return
        extBusy = true; extStatus = "Inspecting…"; plan = null
        viewModelScope.launch {
            try { plan = extManager.inspect(input); extStatus = "" }
            catch (e: Exception) { extStatus = "Couldn't inspect: ${e.message}" }
            finally { extBusy = false }
        }
    }

    fun cancelPlan() { plan = null }

    /** Approved plan: download pinned files, register skills/agents, then install dependencies inside Linux. */
    fun approvePlan() {
        val p = plan ?: return
        if (extBusy) return
        extBusy = true; plan = null
        viewModelScope.launch {
            try {
                var inst = extManager.install(p) { extStatus = it }
                val id = p.manifest.id
                // Skills and agents become slash commands; off by default so they don't grow every prompt.
                val base = "Reference files for this skill are in /root/agentic-os/extensions/$id/ (read them with the shell tool when needed)."
                val newSkills = p.manifest.skills.mapNotNull { sk -> extManager.read(id, sk.file)?.let { txt ->
                    Skill(sk.name, sk.description.ifBlank { "Skill from ${p.repo}" }, "$txt\n\n$base", source = "ext:$id")
                } } + p.manifest.agents.mapNotNull { ag -> extManager.read(id, ag.file)?.let { txt ->
                    Skill(ag.name, ag.description, txt, source = "ext:$id")
                } }
                saveSkills(skills.filterNot { it.source == "ext:$id" } + newSkills)
                if (p.commands.isNotEmpty()) {
                    if (!linuxInstalled) {
                        inst = inst.copy(log = "Dependencies need Linux: install Ubuntu in Workspace → Terminal, then tap Set up.")
                    } else {
                        extStatus = "Installing dependencies in Linux…"
                        val out = com.syntra.os.device.Linux.run(getApplication(), p.commands.joinToString(" && "), 1_200_000)
                        inst = inst.copy(toolsReady = out.startsWith("exit 0"), log = out.takeLast(1500))
                    }
                }
                saveExtensions(extensions.filterNot { it.manifest.id == id } + inst)
                extStatus = "Installed ${p.manifest.name}: ${p.manifest.skills.size} skills, ${p.manifest.agents.size} agents, ${p.manifest.tools.size} tools" +
                    if (p.commands.isNotEmpty() && !inst.toolsReady) " — dependencies not ready" else ""
            } catch (e: Exception) { extStatus = "Install failed: ${e.message}" }
            finally { extBusy = false }
        }
    }

    /** Re-runs the dependency commands (after Linux was installed, or a failed attempt). */
    fun setupExtension(id: String) {
        val e = extensions.firstOrNull { it.manifest.id == id } ?: return
        if (extBusy) return
        extBusy = true; extStatus = "Installing dependencies in Linux…"
        viewModelScope.launch {
            try {
                val cmds = com.syntra.os.ext.Importer.commands(e.manifest, e.sha, e.repo)
                val out = com.syntra.os.device.Linux.run(getApplication(), cmds.joinToString(" && ").ifBlank { "true" }, 1_200_000)
                saveExtensions(extensions.map { if (it.manifest.id == id) it.copy(toolsReady = out.startsWith("exit 0"), log = out.takeLast(1500)) else it })
                extStatus = if (out.startsWith("exit 0")) "${e.manifest.name} is ready" else "Setup failed — see the log on the card"
            } catch (ex: Exception) { extStatus = "Setup failed: ${ex.message}" }
            finally { extBusy = false }
        }
    }

    fun toggleExtension(id: String) = saveExtensions(extensions.map { if (it.manifest.id == id) it.copy(enabled = !it.enabled) else it })

    fun removeExtension(id: String) {
        extManager.remove(id)
        saveSkills(skills.filterNot { it.source == "ext:$id" })
        saveExtensions(extensions.filterNot { it.manifest.id == id })
        extUpdates.remove(id)
    }

    fun checkUpdates() = viewModelScope.launch {
        extensions.forEach { e -> extManager.latestSha(e.repo)?.let { if (it != e.sha) extUpdates[e.manifest.id] = it } }
        extStatus = if (extUpdates.isEmpty()) "Everything is up to date" else "${extUpdates.size} update(s) available"
    }

    /** Updates re-inspect so the user sees the new plan (files, commands, permissions) before approving. */
    fun updateExtension(id: String) { extensions.firstOrNull { it.manifest.id == id }?.let { inspectExtension(it.repo) } }

    // ---- Resumable tasks ----
    private val checkpoints = com.syntra.os.agent.CheckpointStore(java.io.File(app.filesDir, "tasks"))

    /** Task ids that can be continued from a saved checkpoint. */
    fun resumable(): Set<String> = checkpoints.all().map { it.id }.toSet()

    fun resumeTask(id: String) {
        if (busy) return
        val cp = checkpoints.load(id) ?: return
        busy = true; streaming = ""; currentTaskId = id
        editTask(id) { it.copy(status = TaskStatus.RUNNING) }
        chat = chat + Step(StepKind.USER, "you · resume", cp.goal)
        job = viewModelScope.launch {
            val e = env { step -> status = "${step.who}: ${step.text}".take(90); editTask(id) { it.copy(steps = it.steps + step) } }
            try {
                val agent = Agent(e)
                val answer = agent.resume(cp)
                val rep = agent.lastReport
                val done = Step(StepKind.ANSWER, "syntra", answer, files = rep.artifacts.map { it.path }, verified = rep.verified, notes = rep.failures, taskId = id)
                chat = chat + done
                editTask(id) { it.copy(status = TaskStatus.DONE, steps = it.steps + done) }
                lastAnswer = answer
            } catch (ex: CancellationException) { editTask(id) { it.copy(status = TaskStatus.INTERRUPTED) } }
            catch (ex: Exception) {
                editTask(id) { it.copy(status = TaskStatus.INTERRUPTED, steps = it.steps + Step(StepKind.ERROR, "system", ex.message.orEmpty())) }
                chat = chat + Step(StepKind.ERROR, "system", ex.message ?: ex.toString())
            } finally {
                busy = false; status = ""; streaming = ""; pendingApproval = null
                persistChat(); store.save("tasks", ListSerializer(TaskRun.serializer()), tasks)
            }
        }
    }

    fun discardCheckpoint(id: String) { checkpoints.delete(id); editTask(id) { it.copy(status = TaskStatus.CANCELLED) } }

    /** Background jobs write graph/tasks files directly; pick their changes up when the app returns. */
    fun reload() {
        if (busy) return
        graph = store.load("graph", Graph.serializer(), graph)
        tasks = store.load("tasks", ListSerializer(TaskRun.serializer()), tasks)
        schedules = runCatching { com.syntra.os.agent.Scheduler.list(getApplication()) }.getOrDefault(emptyList())
    }

    var schedules by mutableStateOf(listOf<Pair<String, String>>()); private set

    fun cancelSchedule(id: String) {
        com.syntra.os.agent.Scheduler.cancel(getApplication(), id)
        schedules = schedules.filterNot { it.first == id }
    }

    val isLite get() = getApplication<Application>().packageName.endsWith(".lite")

    // ---- Built-in Ubuntu (proot) ----
    val linuxLines = androidx.compose.runtime.mutableStateListOf<String>()
    var linuxStatus by mutableStateOf(""); private set
    var linuxInstalled by mutableStateOf(com.syntra.os.device.Linux.installed(app)); private set
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val linux = com.syntra.os.device.LinuxSession(app) { line ->
        mainHandler.post { linuxLines += line; if (linuxLines.size > 2000) linuxLines.removeRange(0, 500) }
    }

    fun installLinux() = viewModelScope.launch {
        try {
            com.syntra.os.device.Linux.install(getApplication()) { linuxStatus = it }
            com.syntra.os.device.Linux.configure(getApplication(), settings.localApiKey)
            linuxInstalled = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { linux.start() }
        }
        catch (e: Exception) { linuxStatus = "Install failed: ${e.message}" }
    }

    // ---- Terminal AI mode: the coding harness drives Syntra's Linux from a prompt ----
    var termAiProvider by mutableStateOf(com.syntra.os.core.ON_DEVICE)
    var termAiModel by mutableStateOf("deepseek-chat")
    var termBusy by mutableStateOf(false); private set
    private var termJob: Job? = null

    fun terminalAi(prompt: String) {
        if (termBusy || prompt.isBlank()) return
        linuxLines += "» $prompt"
        val cloud = termAiProvider != com.syntra.os.core.ON_DEVICE
        // Same agent, Code mode; for cloud, the lead slot points at the chosen provider/model for this run only.
        val s = if (!cloud) settings else settings.copy(slots = settings.slots.map {
            if (it.role == settings.primaryRole) it.copy(providerId = termAiProvider, model = termAiModel.trim()) else it })
        termBusy = true
        termJob = viewModelScope.launch {
            val e = AgentEnv(getApplication(), s, skills, engines, { graph }, ::updateGraph, log = { st ->
                val line = when (st.kind) {
                    StepKind.TOOL_CALL -> "▸ ${st.who} ${st.text.take(220)}"
                    StepKind.TOOL_RESULT -> st.text.lines().take(12).joinToString("\n") { "  $it" }
                    else -> "· ${st.text.take(220)}"
                }
                mainHandler.post { line.lines().forEach { linuxLines += it } }
            }, approve = ::askApproval)
            try {
                val answer = Agent(e).run(prompt, emptyList(), code = true)
                answer.lines().forEach { linuxLines += "✓ $it" }
            } catch (ex: CancellationException) { linuxLines += "[stopped]" }
            catch (ex: Exception) { linuxLines += "✗ ${ex.message}" }
            finally { termBusy = false; pendingApproval = null }
        }
    }

    fun stopTerminalAi() { approval?.complete(false); engines.stopAll(); termJob?.cancel() }

    fun installedLinuxTools() = com.syntra.os.device.Linux.installedTools(getApplication())

    // Starting a shell may probe proot variants (seconds), so never on the main thread.
    fun linuxSend(cmd: String) { linuxLines += "$ $cmd"; viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { linux.start(); linux.send(cmd) } }
    fun linuxRestart() { linux.stop(); linuxLines.clear(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { linux.start() } }
    /** Forget the chosen proot variant and probe again (shows what each variant printed). */
    fun linuxReprobe() {
        linux.stop(); linuxLines.clear()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.syntra.os.device.Linux.resetVariant(getApplication())
            val v = com.syntra.os.device.Linux.variant(getApplication())
            mainHandler.post { linuxLines += "[setup] using ${v.name}"; com.syntra.os.device.Linux.lastProbe.lines().filter { it.isNotBlank() }.forEach { linuxLines += "[probe] $it" } }
            linux.start()
        }
    }

    override fun onCleared() { linux.stop(); server.stop(); super.onCleared() }

    // ---- Remote access over Telegram ("access from anywhere") ----
    private var telegramJob: Job? = null
    var telegramStatus by mutableStateOf(""); private set

    fun startTelegram() {
        telegramJob?.cancel()
        val token = settings.telegramToken.trim()
        if (token.isBlank()) { telegramStatus = ""; return }
        telegramJob = viewModelScope.launch {
            var offset = 0L
            telegramStatus = "Connecting…"
            while (isActive) {
                try {
                    val r = com.syntra.os.core.httpCall("https://api.telegram.org/bot$token/getUpdates?timeout=50&offset=$offset")
                    if (r.code != 200) { telegramStatus = "Telegram error ${r.code}"; delay(15_000); continue }
                    telegramStatus = "Listening on Telegram"
                    val updates = (com.syntra.os.core.json.parseToJsonElement(r.body).jsonObject["result"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                    for (u in updates) {
                        val o = u.jsonObject
                        offset = (o["update_id"]?.toString()?.toLongOrNull() ?: 0) + 1
                        val msg = o["message"]?.jsonObject ?: continue
                        val chatIdStr = msg["chat"]?.jsonObject?.get("id")?.toString() ?: continue
                        val text = (msg["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                        val allowed = settings.telegramChats.split(',').map { it.trim() }.filter { it.isNotBlank() }
                        val reply = if (chatIdStr !in allowed) "Not authorised. Add chat id $chatIdStr in Syntra → Setup → Automations."
                        else runCatching {
                            // Remote runs can't be approved on the phone, so sensitive tools are denied.
                            val e2 = AgentEnv(getApplication(), settings, skills, engines, { graph }, ::updateGraph, log = {}, approve = { false })
                            val slash = com.syntra.os.agent.AgenticOs.parseSlash(text, skills)
                            Agent(e2).run(slash?.second?.ifBlank { text } ?: text, emptyList(), skill = slash?.first, code = text.startsWith("/code"))
                        }.getOrElse { "Error: ${it.message}" }
                        com.syntra.os.core.httpCall("https://api.telegram.org/bot$token/sendMessage", "POST",
                            kotlinx.serialization.json.buildJsonObject { put("chat_id", kotlinx.serialization.json.JsonPrimitive(chatIdStr)); put("text", kotlinx.serialization.json.JsonPrimitive(reply.take(4000))) })
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { telegramStatus = "Telegram: ${e.message}"; delay(15_000) }
            }
        }
    }

    fun cancel() { approval?.complete(false); engines.stopAll(); job?.cancel() }

    private suspend fun askApproval(action: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        approval = d
        pendingApproval = action
        lastAnswer = "I need your approval."
        val ok = d.await()
        pendingApproval = null
        return ok
    }

    fun answerApproval(ok: Boolean) { approval?.complete(ok) }

    private fun editTask(id: String, f: (TaskRun) -> TaskRun) { tasks = tasks.map { if (it.id == id) f(it) else it } }

    fun downloadModel(role: ModelRole) {
        if (role in downloads) return
        downloads[role] = "Starting…"
        viewModelScope.launch {
            try {
                models.download(role) { downloads[role] = it }
                downloads.remove(role)
            } catch (e: Exception) {
                downloads[role] = "Failed: ${e.message}"
                delay(6000); downloads.remove(role)
            }
            installedTick++
        }
    }

    // ---- Any-model support (Models screen) ----
    var customModels by mutableStateOf(models.customModels()); private set
    val modelInfo = mutableStateMapOf<String, String>() // id → capability / benchmark text
    var modelBusy by mutableStateOf<String?>(null); private set

    private fun applyOverrides() {
        models.overrides = settings.mainModelId?.takeIf { it in models.customModels() }?.let { mapOf(settings.primaryRole to it) } ?: emptyMap()
    }

    fun installCustom(repo: String) {
        if (modelBusy != null) return
        modelBusy = repo
        viewModelScope.launch {
            try {
                val id = models.downloadCustom(repo) { downloads[ModelRole.OMNI] = it; modelInfo[repo] = it }
                modelInfo[id] = "Installed. Tap Probe to detect capabilities."
                modelInfo.remove(repo)
            } catch (e: Exception) { modelInfo[repo] = "Failed: ${e.message}" }
            finally { downloads.remove(ModelRole.OMNI); modelBusy = null; customModels = models.customModels() }
        }
    }

    /** Make an installed custom model the main model (null = back to the built-in default). */
    fun useAsMain(id: String?) {
        updateSettings(settings.copy(mainModelId = id, primaryRole = if (settings.primaryRole == ModelRole.DECISION) ModelRole.OMNI else settings.primaryRole))
        applyOverrides(); engines.unloadAll()
    }

    fun deleteCustom(id: String) {
        if (settings.mainModelId == id) useAsMain(null)
        models.deleteCustom(id); customModels = models.customModels(); modelInfo.remove(id)
    }

    /** Loads the main model (applying [id] as main first) and reports capabilities, or runs a speed test. */
    fun probeModel(id: String, bench: Boolean) {
        if (modelBusy != null) return
        modelBusy = id
        viewModelScope.launch {
            val prevMain = settings.mainModelId
            try {
                if (prevMain != id) { models.overrides = mapOf(settings.primaryRole to id); engines.unloadAll() }
                val caps = engines.probe(settings.primaryRole)
                modelInfo[id] = caps.summary() + if (bench) "\n" + engines.benchmark(settings.primaryRole) else ""
            } catch (e: Exception) { modelInfo[id] = "Failed: ${e.message}" }
            finally { if (prevMain != id) { applyOverrides(); engines.unloadAll() }; modelBusy = null }
        }
    }

    /** Drop loaded models so the next request reloads them with new settings (e.g. GPU on/off). */
    fun reloadModels() = engines.unloadAll()

    fun deleteModel(role: ModelRole) { models.delete(role); installedTick++ }

    fun updateGraph(g: Graph) { graph = g; store.save("graph", Graph.serializer(), g) }
    fun updateSettings(s: Settings) {
        val tg = s.telegramToken != settings.telegramToken
        settings = s; store.save("settings", Settings.serializer(), s)
        if (tg) startTelegram()
    }
    private fun saveSkills(list: List<Skill>) { skills = list; store.save("skills", ListSerializer(Skill.serializer()), list) }
    fun toggleSkill(s: Skill) = saveSkills(skills.map { if (it == s) it.copy(enabled = !it.enabled) else it })
    fun removeSkill(s: Skill) = saveSkills(skills - s)
    fun clearChat() = newChat()

    fun installSkill(url: String, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            val s = Skills.install(url)
            saveSkills(skills.filterNot { it.name == s.name } + s)
            onDone("Installed ${s.name} — review it, then enable.")
        } catch (e: Exception) { onDone("Install failed: ${e.message}") }
    }
}
