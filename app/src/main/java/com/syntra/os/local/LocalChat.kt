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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/** Prompt text plus the length (UTF-8 bytes) of its stable system-prompt prefix, which the engine checkpoints. */
class RenderedPrompt(val text: String, val stableBytes: Int)

/**
 * LFM3's own chat format (models/templates/LFM3.jinja in llama.cpp): tools as JSON lines in <tools>,
 * calls as <tool_call><function=…><parameter=…>, results in <tool_response>. Thinking is switched off
 * with an empty <think></think> so replies start immediately.
 */
object LfmPrompt {
    const val GEN = "<|im_start|>assistant\n<think></think>"

    fun render(messages: List<JsonObject>, tools: JsonArray?, mediaMarker: String?): RenderedPrompt {
        val sb = StringBuilder()
        var stable = 0
        val lastUser = messages.indexOfLast { it.str("role") == "user" }
        messages.forEachIndexed { i, m ->
            val role = m.str("role") ?: "user"
            var content = m.str("content").orEmpty()
            when (role) {
                "system" -> {
                    if (tools != null && tools.isNotEmpty())
                        content += "\n\n<tools>\n" + tools.joinToString("\n") { it.jsonObject["function"].toString() } + "\n</tools>"
                    sb.append("<|im_start|>system\n").append(content).append("<|im_end|>\n")
                    if (i == 0) stable = sb.toString().toByteArray().size
                }
                // Raw generated text is kept verbatim so the next prompt extends the cached tokens exactly.
                "assistant" -> sb.append(GEN).append(m.str("raw") ?: content).append("<|im_end|>\n")
                "tool" -> sb.append("<|im_start|>tool\n<tool_response>\n").append(content).append("\n</tool_response><|im_end|>\n")
                else -> {
                    if (mediaMarker != null && i == lastUser) content = "$mediaMarker\n$content"
                    sb.append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
                }
            }
        }
        sb.append(GEN)
        return RenderedPrompt(sb.toString(), stable)
    }

    private val NATIVE = Regex("<tool_call>\\s*<function=([\\w.-]+)>(.*?)</function>\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val PARAM = Regex("<parameter=([\\w.-]+)>\\n?(.*?)\\n?</parameter>", RegexOption.DOT_MATCHES_ALL)
    // Fallbacks seen from small models: <tool>{json}</tool>, and <tool>name</tool> followed by a JSON object.
    private val TAG_JSON = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)
    private val TAG_NAME = Regex("<tool>\\s*([\\w.-]+)\\s*</tool>")
    private val THINK = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)

    /** Parameter text → JSON: objects/arrays/numbers/booleans/null as-is, everything else a string. */
    private fun value(v: String): JsonElement {
        val t = v.trim()
        val structured = t.startsWith("{") || t.startsWith("[") || t == "true" || t == "false" || t == "null" || t.toDoubleOrNull() != null
        return (if (structured) runCatching { json.parseToJsonElement(t) }.getOrNull() else null) ?: JsonPrimitive(t)
    }

    /** Adds commas small models forget between lines: `"a": "x"\n "b": …`. */
    fun lenient(j: String) = j.replace(Regex("(\"|\\}|\\]|\\d|true|false|null)\\s*\\n(\\s*\")"), "$1,\n$2")

    /** The `{…}` starting at the first non-space char from [from], with nested braces matched. */
    fun balancedObject(t: String, from: Int): String? {
        var i = from
        while (i < t.length && t[i].isWhitespace()) i++
        if (i >= t.length || t[i] != '{') return null
        var depth = 0; var inStr = false; var esc = false
        for (j in i until t.length) {
            val c = t[j]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> if (--depth == 0) return t.substring(i, j + 1) }
        }
        return null
    }

    fun parse(raw: String): ChatReply {
        val text = THINK.replace(raw, "")
        val calls = mutableListOf<ToolCall>()
        var visible = text
        NATIVE.findAll(text).forEach { m ->
            val args = buildJsonObject { PARAM.findAll(m.groupValues[2]).forEach { p -> put(p.groupValues[1], value(p.groupValues[2])) } }
            calls += ToolCall("local${calls.size}", m.groupValues[1], args.toString())
        }
        visible = NATIVE.replace(visible, "")
        if (calls.isEmpty()) {
            TAG_JSON.findAll(text).forEach { m ->
                runCatching {
                    val o = json.parseToJsonElement(m.groupValues[1]).jsonObject
                    calls += ToolCall("local${calls.size}", o.str("name").orEmpty(), (o["arguments"] ?: JsonObject(emptyMap())).toString())
                }
            }
            visible = TAG_JSON.replace(visible, "")
        }
        if (calls.isEmpty()) {
            TAG_NAME.findAll(text).forEach { m ->
                val objText = balancedObject(text, m.range.last + 1)
                val o = objText?.let { runCatching { json.parseToJsonElement(lenient(it)).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
                // {"name": "YouTube", "arguments": {}} here means the app name, so merge both levels into one args object.
                val nested = o["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                calls += ToolCall("local${calls.size}", m.groupValues[1], JsonObject(o - "arguments" + nested).toString())
                if (objText != null) visible = visible.replace(objText, "")
            }
            visible = TAG_NAME.replace(visible, "")
        }
        return ChatReply(visible.trim(), calls, buildJsonObject {
            put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(visible.trim())); put("raw", JsonPrimitive(raw))
        })
    }
}

/**
 * On-device engines, one per role. Each has its own llama context and lock, so different
 * models generate truly in parallel while one model serves one request at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalEngines(private val models: ModelManager) {
    private class Loaded(val handle: Long, val media: Boolean, var lastUse: Long)
    private val loaded = java.util.concurrent.ConcurrentHashMap<ModelRole, Loaded>()
    private val locks = ModelRole.entries.associateWith { Mutex() }
    private val loadLock = Mutex()
    private val pool = Dispatchers.Default.limitedParallelism(ModelRole.entries.size)
    /** Roles currently generating; the UI lights these up. */
    @Volatile var active: Set<ModelRole> = emptySet(); private set

    private suspend fun engine(role: ModelRole): Loaded = loadLock.withLock {
        loaded[role]?.let { it.lastUse = System.currentTimeMillis(); return it }
        val files = models.installed(role) ?: error("${role.label} model not downloaded — open Setup → On-device models")
        // shortcut: keep at most 2 models resident (~2.5 GB) to avoid low-memory kills; raise on 16 GB phones.
        if (loaded.size >= 2) loaded.entries.filter { !locks.getValue(it.key).isLocked }.minByOrNull { it.value.lastUse }?.let {
            loaded.remove(it.key); LlamaNative.free(it.value.handle)
        }
        // Big cores only: mixing in little cores makes every token wait for the slowest thread.
        val threads = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2
        val h = withContext(pool) { LlamaNative.load(files.model.path, files.mmproj?.path, 8192, threads) }
        Loaded(h, LlamaNative.hasMedia(h), System.currentTimeMillis()).also { loaded[role] = it }
    }

    suspend fun complete(role: ModelRole, messages: List<JsonObject>, tools: JsonArray?, media: String?, onText: ((String) -> Unit)?): ChatReply =
        locks.getValue(role).withLock {
            val e = engine(role)
            val useMedia = media?.takeIf { e.media }
            val p = LfmPrompt.render(messages, tools, if (useMedia != null) LlamaNative.marker() else null)
            active = active + role
            try {
                val buf = ByteArrayOutputStream()
                var lastPush = 0L
                val bytes = withContext(pool) {
                    LlamaNative.generate(e.handle, p.text, useMedia, 512, { piece ->
                        buf.write(piece)
                        val now = System.currentTimeMillis()
                        if (onText != null && now - lastPush > 120) { lastPush = now; onText(buf.toString(Charsets.UTF_8.name())) }
                        true
                    }, p.stableBytes)
                }
                LfmPrompt.parse(String(bytes, Charsets.UTF_8))
            } finally { active = active - role }
        }

    fun stopAll() = loaded.values.toList().forEach { LlamaNative.stop(it.handle) }
    fun isLoaded(role: ModelRole) = loaded.containsKey(role)
}
