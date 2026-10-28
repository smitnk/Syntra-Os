package com.syntra.os

import android.app.Application
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

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app.filesDir)
    val models = ModelManager(app.filesDir)
    val engines = LocalEngines(models)

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

    init {
        java.io.File(app.filesDir, "crash.txt").takeIf { it.exists() }?.let { f ->
            chat = listOf(Step(StepKind.ERROR, "system", "Syntra crashed last time:\n" + f.readText().lines().take(6).joinToString("\n")))
            f.delete()
        }
        // Light up the model-team row while engines generate.
        viewModelScope.launch { while (isActive) { activeRoles = engines.active; delay(150) } }
    }

    /** Older installs lack the on-device / OpenClaw / Hermes providers. */
    private fun migrate(s: Settings): Settings {
        val missing = DEFAULT_PROVIDERS.filter { d -> s.providers.none { it.id == d.id } }
        return if (missing.isEmpty()) s else s.copy(providers = missing + s.providers)
    }

    private fun env(log: (Step) -> Unit = {}) = AgentEnv(getApplication(), settings, skills, engines, { graph }, ::updateGraph,
        log = log, approve = ::askApproval, onStream = { streaming = it })

    fun send(text: String) {
        if (text.isBlank() || busy) return
        val history = chat.mapNotNull {
            when (it.kind) { StepKind.USER -> message("user", it.text); StepKind.ANSWER -> message("assistant", it.text); else -> null }
        }
        val image = attachment
        attachment = null
        chat = chat + Step(StepKind.USER, if (image != null) "you · image" else "you", text)
        val task = TaskRun(id = System.currentTimeMillis().toString(), goal = text)
        tasks = (listOf(task) + tasks).take(30)
        busy = true
        streaming = ""
        job = viewModelScope.launch {
            val e = env { step -> status = "${step.who}: ${step.text}".take(90); editTask(task.id) { it.copy(steps = it.steps + step) } }
            try {
                val answer = Agent(e).run(text, history, image)
                chat = chat + Step(StepKind.ANSWER, settings.slot(settings.primaryRole).model, answer)
                editTask(task.id) { it.copy(status = TaskStatus.DONE, steps = it.steps + Step(StepKind.ANSWER, "syntra", answer)) }
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
        try { com.syntra.os.device.Linux.install(getApplication()) { linuxStatus = it }; linuxInstalled = true; linux.start() }
        catch (e: Exception) { linuxStatus = "Install failed: ${e.message}" }
    }

    fun linuxSend(cmd: String) { linuxLines += "$ $cmd"; linux.start(); linux.send(cmd) }
    fun linuxRestart() { linux.stop(); linuxLines.clear(); linux.start() }

    override fun onCleared() { linux.stop(); super.onCleared() }

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

    fun deleteModel(role: ModelRole) { models.delete(role); installedTick++ }

    fun updateGraph(g: Graph) { graph = g; store.save("graph", Graph.serializer(), g) }
    fun updateSettings(s: Settings) { settings = s; store.save("settings", Settings.serializer(), s) }
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
