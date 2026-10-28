package com.syntra.os.local

import com.syntra.os.core.ChatReply
import com.syntra.os.core.ModelRole
import com.syntra.os.core.ToolCall
import com.syntra.os.core.json
import com.syntra.os.core.message
import com.syntra.os.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/** Renders OpenAI-style messages into LFM's ChatML template. Tools become a text protocol small models follow well. */
object LfmPrompt {
    fun render(messages: List<JsonObject>, tools: JsonArray?, mediaMarker: String?): String = buildString {
        messages.forEachIndexed { i, m ->
            val role = m.str("role") ?: "user"
            var content = m.str("content").orEmpty()
            if (role == "system" && tools != null && tools.isNotEmpty()) content += toolSection(tools)
            if (mediaMarker != null && i == messages.indexOfLast { it.str("role") == "user" }) content = "$mediaMarker\n$content"
            append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    private fun toolSection(tools: JsonArray) = buildString {
        append("\n\n# Tools\nTo use a tool, reply with ONLY one or more lines like:\n")
        append("<tool>{\"name\": \"tool_name\", \"arguments\": {...}}</tool>\n")
        append("Several <tool> lines run in parallel. After results arrive, continue or give the final answer as plain text.\n")
        tools.forEach { t ->
            val f = t.jsonObject["function"]!!.jsonObject
            append("- ").append(f.str("name")).append(": ").append(f.str("description"))
            append(" params=").append(f["parameters"]?.jsonObject?.get("properties")).append('\n')
        }
    }

    private val TOOL = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): ChatReply {
        val calls = TOOL.findAll(text).mapIndexedNotNull { i, m ->
            runCatching {
                val o = json.parseToJsonElement(m.groupValues[1]).jsonObject
                ToolCall("local$i", o.str("name").orEmpty(), (o["arguments"] ?: JsonObject(emptyMap())).toString())
            }.getOrNull()
        }.toList()
        val visible = TOOL.replace(text, "").trim()
        return ChatReply(visible, calls, message("assistant", text.trim()))
    }
}

/**
 * On-device engines, one per role. Each has its own llama context and lock, so different
 * models generate truly in parallel while one model serves one request at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalEngines(private val models: ModelManager) {
    private class Loaded(val handle: Long, val media: Boolean, var lastUse: Long)
    private val loaded = HashMap<ModelRole, Loaded>()
    private val locks = ModelRole.entries.associateWith { Mutex() }
    private val loadLock = Mutex()
    private val pool = Dispatchers.Default.limitedParallelism(ModelRole.entries.size)
    /** Roles currently generating; the UI lights these up. */
    @Volatile var active: Set<ModelRole> = emptySet(); private set

    private suspend fun engine(role: ModelRole): Loaded = loadLock.withLock {
        loaded[role]?.let { it.lastUse = System.currentTimeMillis(); return it }
        val files = models.installed(role) ?: error("${role.label} model not downloaded — open Setup → On-device models")
        // shortcut: keep at most 3 models resident (~3.5 GB); raise on 16 GB phones.
        if (loaded.size >= 3) loaded.entries.filter { !locks.getValue(it.key).isLocked }.minByOrNull { it.value.lastUse }?.let {
            LlamaNative.free(it.value.handle); loaded.remove(it.key)
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val h = withContext(pool) { LlamaNative.load(files.model.path, files.mmproj?.path, 8192, threads) }
        Loaded(h, LlamaNative.hasMedia(h), System.currentTimeMillis()).also { loaded[role] = it }
    }

    suspend fun complete(role: ModelRole, messages: List<JsonObject>, tools: JsonArray?, media: String?, onText: ((String) -> Unit)?): ChatReply =
        locks.getValue(role).withLock {
            val e = engine(role)
            val useMedia = media?.takeIf { e.media }
            val prompt = LfmPrompt.render(messages, tools, if (useMedia != null) LlamaNative.marker() else null)
            active = active + role
            try {
                val buf = ByteArrayOutputStream()
                val bytes = withContext(pool) {
                    LlamaNative.generate(e.handle, prompt, useMedia, 768) { piece ->
                        buf.write(piece)
                        onText?.invoke(buf.toString(Charsets.UTF_8.name()))
                        true
                    }
                }
                LfmPrompt.parse(String(bytes, Charsets.UTF_8))
            } finally { active = active - role }
        }

    fun stopAll() = loaded.values.forEach { LlamaNative.stop(it.handle) }
    fun isLoaded(role: ModelRole) = role in loaded
}
