package com.syntra.os

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syntra.os.agent.Agent
import com.syntra.os.agent.Skills
import com.syntra.os.core.Settings
import com.syntra.os.core.Skill
import com.syntra.os.core.Step
import com.syntra.os.core.StepKind
import com.syntra.os.core.Store
import com.syntra.os.core.TaskRun
import com.syntra.os.core.TaskStatus
import com.syntra.os.core.message
import com.syntra.os.memory.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app.filesDir)

    var settings by mutableStateOf(store.load("settings", Settings.serializer(), Settings())); private set
    var graph by mutableStateOf(store.load("graph", Graph.serializer(), Graph())); private set
    var skills by mutableStateOf(store.load("skills", ListSerializer(Skill.serializer()), Skills.builtIn)); private set
    var tasks by mutableStateOf(store.load("tasks", ListSerializer(TaskRun.serializer()), emptyList())); private set
    var chat by mutableStateOf(listOf<Step>()); private set
    var pendingApproval by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    /** Set after an answer arrives so the UI can speak it. */
    var lastAnswer by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null

    fun send(text: String) {
        if (text.isBlank() || busy) return
        val history = chat.mapNotNull {
            when (it.kind) { StepKind.USER -> message("user", it.text); StepKind.ANSWER -> message("assistant", it.text); else -> null }
        }
        chat = chat + Step(StepKind.USER, "you", text)
        val task = TaskRun(id = System.currentTimeMillis().toString(), goal = text)
        tasks = (listOf(task) + tasks).take(30)
        busy = true
        job = viewModelScope.launch {
            val agent = Agent(settings, skills, { graph }, ::updateGraph,
                log = { step -> status = step.text.take(80); editTask(task.id) { it.copy(steps = it.steps + step) } },
                approve = ::askApproval)
            try {
                val answer = agent.run(text, history)
                chat = chat + Step(StepKind.ANSWER, settings.slot(settings.primaryRole).model, answer)
                editTask(task.id) { it.copy(status = TaskStatus.DONE, steps = it.steps + Step(StepKind.ANSWER, "syntra", answer)) }
                lastAnswer = answer
            } catch (e: CancellationException) {
                editTask(task.id) { it.copy(status = TaskStatus.CANCELLED) }
                chat = chat + Step(StepKind.ERROR, "system", "Cancelled.")
            } catch (e: Exception) {
                editTask(task.id) { it.copy(status = TaskStatus.FAILED, steps = it.steps + Step(StepKind.ERROR, "system", e.message.orEmpty())) }
                chat = chat + Step(StepKind.ERROR, "system", e.message ?: e.toString())
            } finally {
                busy = false; status = ""; pendingApproval = null
                store.save("tasks", ListSerializer(TaskRun.serializer()), tasks)
            }
        }
    }

    fun cancel() { approval?.complete(false); job?.cancel() }

    private suspend fun askApproval(action: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        approval = d
        pendingApproval = action
        val ok = d.await()
        pendingApproval = null
        return ok
    }

    fun answerApproval(ok: Boolean) { approval?.complete(ok) }

    private fun editTask(id: String, f: (TaskRun) -> TaskRun) {
        tasks = tasks.map { if (it.id == id) f(it) else it }
    }

    fun updateGraph(g: Graph) { graph = g; store.save("graph", Graph.serializer(), g) }
    fun updateSettings(s: Settings) { settings = s; store.save("settings", Settings.serializer(), s) }
    private fun saveSkills(list: List<Skill>) { skills = list; store.save("skills", ListSerializer(Skill.serializer()), list) }
    fun toggleSkill(s: Skill) = saveSkills(skills.map { if (it == s) it.copy(enabled = !it.enabled) else it })
    fun removeSkill(s: Skill) = saveSkills(skills - s)
    fun clearChat() { chat = emptyList() }

    fun installSkill(url: String, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            val s = Skills.install(url)
            saveSkills(skills.filterNot { it.name == s.name } + s)
            onDone("Installed ${s.name} — review it, then enable.")
        } catch (e: Exception) { onDone("Install failed: ${e.message}") }
    }
}
