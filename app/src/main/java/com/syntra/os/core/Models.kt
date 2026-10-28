package com.syntra.os.core

import kotlinx.serialization.Serializable

/** Roles mirror the LFM3 family: text, vision, audio, omni-generative, omni-decision. */
enum class ModelRole(val label: String, val defaultModel: String) {
    TEXT("Text", "LFM3-1.7B"),
    VISION("Vision", "LFM3-VL-2B"),
    AUDIO("Audio", "LFM3-Audio-2B"),
    OMNI("Omni · Generative", "LFM3-Omni-2B"),
    DECISION("Omni · Decision", "d1-Omni-2B"),
}

/** Any OpenAI-compatible endpoint: Hermes Agent, OpenRouter, Liquid, llama.cpp / Ollama in Termux, etc. */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
)

@Serializable
data class ModelSlot(val role: ModelRole, val providerId: String = "", val model: String = role.defaultModel)

@Serializable
data class McpServer(val name: String, val url: String, val token: String = "")

@Serializable
data class Settings(
    val providers: List<Provider> = listOf(
        Provider("local", "Local (Termux llama-server / Hermes)", "http://127.0.0.1:8080/v1"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
    ),
    val slots: List<ModelSlot> = ModelRole.entries.map { ModelSlot(it, "local") },
    val primaryRole: ModelRole = ModelRole.TEXT,
    val mcpServers: List<McpServer> = emptyList(),
    val githubToken: String = "",
    val speakReplies: Boolean = true,
) {
    fun slot(role: ModelRole) = slots.firstOrNull { it.role == role } ?: ModelSlot(role)
    fun provider(id: String) = providers.firstOrNull { it.id == id }
}

@Serializable
data class Skill(
    val name: String,
    val description: String,
    val prompt: String,
    val source: String = "built-in",
    val enabled: Boolean = false,
)

enum class StepKind { USER, THOUGHT, TOOL_CALL, TOOL_RESULT, ANSWER, ERROR, APPROVAL }

@Serializable
data class Step(val kind: StepKind, val who: String, val text: String)

enum class TaskStatus { RUNNING, WAITING_APPROVAL, DONE, FAILED, CANCELLED }

@Serializable
data class TaskRun(
    val id: String,
    val goal: String,
    val status: TaskStatus = TaskStatus.RUNNING,
    val steps: List<Step> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
)
