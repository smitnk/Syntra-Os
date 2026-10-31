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

const val ON_DEVICE = "on-device"

/** On-device llama.cpp, or any OpenAI-compatible endpoint: OpenClaw gateway, Hermes Agent, OpenRouter, Liquid, Ollama in Termux. */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    /** Model/agent id used when this provider is a delegate agent (e.g. "openclaw"). */
    val agentModel: String = "",
) {
    val local get() = baseUrl == ON_DEVICE
}

val DEFAULT_PROVIDERS = listOf(
    Provider(ON_DEVICE, "On-device (llama.cpp)", ON_DEVICE),
    Provider("openclaw", "OpenClaw Gateway", "http://127.0.0.1:18789/v1", agentModel = "openclaw"),
    Provider("hermes", "Hermes Agent", "http://127.0.0.1:8642/v1", agentModel = "hermes-agent"),
    Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
    Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
)

@Serializable
data class ModelSlot(val role: ModelRole, val providerId: String = "", val model: String = role.defaultModel)

@Serializable
data class McpServer(val name: String, val url: String, val token: String = "")

@Serializable
data class Settings(
    val providers: List<Provider> = DEFAULT_PROVIDERS,
    val slots: List<ModelSlot> = ModelRole.entries.map { ModelSlot(it, ON_DEVICE) },
    val primaryRole: ModelRole = ModelRole.OMNI,
    val mcpServers: List<McpServer> = emptyList(),
    val githubToken: String = "",
    val speakReplies: Boolean = true,
    val alwaysListening: Boolean = true,
    val wakeWords: String = "syntra, jarvis",
    val askPhoneActions: Boolean = true,
    val autoMemory: Boolean = true,
    val teamPlanning: Boolean = false, // unused since v0.7 (d1 is a classifier); kept so old settings files load
    /** Ask the d1 classifier to route requests keywords can't place (swaps models on 8 GB phones). */
    val useD1Router: Boolean = false,
    /** Offload model layers to the GPU (Vulkan builds only). */
    val useGpu: Boolean = false,
    /** One-time switch of the default lead model to Omni once it's downloaded. */
    val omniDefaultApplied: Boolean = false,
    /** Installed custom GGUF used as the main (lead) model instead of the role's default; null = default. */
    val mainModelId: String? = null,
    /** Ask before shell / code_agent / Hermes run in the app's Ubuntu sandbox. */
    val askLinux: Boolean = true,
    val telegramToken: String = "",
    /** Chat ids allowed to command Syntra over Telegram, comma-separated. */
    val telegramChats: String = "",
    /** Random key guarding the local OpenAI-compatible API (127.0.0.1:8099). */
    val localApiKey: String = "sk-syntra-" + java.util.UUID.randomUUID().toString().replace("-", ""),
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

@Serializable
data class ChatSession(val id: String, val title: String, val steps: List<Step>, val updated: Long = System.currentTimeMillis())
