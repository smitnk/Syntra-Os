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
            var content = textOf(m)
            when (role) {
                "system" -> {
                    if (tools != null && tools.isNotEmpty()) content += "\n\n<tools>\n" + tools.joinToString("\n") { compactTool(it) } + "\n</tools>"
                    sb.append("<|im_start|>system\n").append(content).append("<|im_end|>\n")
                    if (i == 0) stable = sb.toString().toByteArray().size
                }
                // Raw generated text is kept verbatim so the next prompt extends the cached tokens exactly.
                "assistant" -> sb.append(GEN).append(m.str("raw") ?: (content + toolCallMarkup(m))).append("<|im_end|>\n")
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

    /** Shorter than the full JSON schema (fewer tokens to read) while keeping names, descriptions and required args. */
    fun compactTool(t: kotlinx.serialization.json.JsonElement): String {
        val f = t.jsonObject["function"]?.jsonObject ?: t.jsonObject
        val params = f["parameters"]?.jsonObject
        val props = params?.get("properties")?.jsonObject.orEmpty().mapValues { (_, v) -> JsonPrimitive(v.jsonObject.str("description").orEmpty()) }
        return buildJsonObject {
            put("name", f["name"] ?: JsonPrimitive(""))
            put("description", f["description"] ?: JsonPrimitive(""))
            put("parameters", JsonObject(props))
            params?.get("required")?.let { put("required", it) }
        }.toString()
    }

    /** OpenAI message content can be a string or a list of {type:text,text} parts. */
    fun textOf(m: JsonObject): String = when (val c = m["content"]) {
        is JsonPrimitive -> if (c.isString) c.content else ""
        is JsonArray -> c.joinToString("") { (it as? JsonObject)?.str("text") ?: "" }
        else -> ""
    }

    /** OpenAI assistant tool_calls rendered in LFM3's own markup (for clients such as Hermes or Aider). */
    private fun toolCallMarkup(m: JsonObject): String = (m["tool_calls"] as? JsonArray).orEmpty().joinToString("\n\n", prefix = "") { tc ->
        val fn = tc.jsonObject["function"]?.jsonObject ?: return@joinToString ""
        val args = runCatching { json.parseToJsonElement(fn.str("arguments") ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        "<tool_call>\n<function=${fn.str("name")}>\n" + args.entries.joinToString("") { (k, v) ->
            "<parameter=$k>\n${if (v is JsonPrimitive && v.isString) v.content else v.toString()}\n</parameter>\n"
        } + "</function>\n</tool_call>"
    }

    private val JSON_CALL = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)

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
        if (calls.isEmpty()) { // Qwen/Hermes/generic: <tool_call>{"name":…,"arguments":{…}}</tool_call>
            JSON_CALL.findAll(text).forEach { m -> runCatching {
                val o = json.parseToJsonElement(lenient(m.groupValues[1])).jsonObject
                calls += ToolCall("local${calls.size}", o.str("name").orEmpty(), (o["arguments"] ?: o["parameters"] ?: JsonObject(emptyMap())).toString())
            } }
            visible = JSON_CALL.replace(visible, "")
        }
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
class LocalEngines(private val models: ModelManager, private val ctx: android.content.Context,
                   /** 0 = CPU only; >0 = layers offloaded to the GPU (Vulkan builds only). */
                   val gpuLayers: () -> Int = { 0 }) {
    private class Loaded(val handle: Long, val media: Boolean, var lastUse: Long, val dir: java.io.File, val caps: Capabilities) { var bg: Long = 0; var cpKey = "" }
    private val loaded = java.util.concurrent.ConcurrentHashMap<ModelRole, Loaded>()
    private val locks = ModelRole.entries.associateWith { Mutex() }
    private val bgLocks = ModelRole.entries.associateWith { Mutex() }
    private val loadLock = Mutex()
    /**
     * One generation at a time across all models. On a phone, two 2B models decoding together saturate
     * CPU and RAM and can starve System UI (the v0.6 freeze). "Parallel" teammates take turns instead.
     */
    private val compute = Mutex()
    private val pool = Dispatchers.Default.limitedParallelism(2)
    /** Roles currently generating; the UI lights these up. */
    @Volatile var active: Set<ModelRole> = emptySet(); private set

    fun installedRoles() = ModelRole.entries.filter { models.installed(it) != null }

    private fun memInfo() = android.app.ActivityManager.MemoryInfo().also {
        ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
    }

    private fun freeLoaded(r: ModelRole) { loaded.remove(r)?.let { l -> if (l.bg != 0L) LlamaNative.free(l.bg); LlamaNative.free(l.handle) } }

    private suspend fun engine(role: ModelRole): Loaded = loadLock.withLock {
        loaded[role]?.let { it.lastUse = System.currentTimeMillis(); return it }
        val files = models.installed(role) ?: error("${role.label} model not downloaded — open Setup → On-device models")
        val need = files.model.length() + (files.mmproj?.length() ?: 0) + 600_000_000L // weights + KV/compute buffers
        // Phones under ~11 GB keep one model in RAM; bigger phones two.
        val maxResident = if (memInfo().totalMem > 11_000_000_000L) 2 else 1
        while (loaded.size >= maxResident || (loaded.isNotEmpty() && memInfo().availMem - need < 1_500_000_000L)) {
            val victim = loaded.entries.filter { !locks.getValue(it.key).isLocked && !bgLocks.getValue(it.key).isLocked }
                .minByOrNull { it.value.lastUse }?.key ?: break
            freeLoaded(victim)
        }
        // No hard refusal: Android's "available" excludes cached apps it will reclaim on demand (v0.7 refused wrongly).
        // The low-memory killer handles real pressure; inference runs at background priority so System UI stays safe.
        val threads = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2 // big cores only; leave the rest for the UI
        val h = withContext(pool) { background(); LlamaNative.load(files.model.path, files.mmproj?.path, 4096, threads, threads, gpuLayers()) }
        val info = LlamaNative.info(h)
        val caps = Capabilities(arch = info[0], contextTrain = info[1].toIntOrNull() ?: 0, params = info[2].toLongOrNull() ?: 0,
            description = info[3], hasTemplate = info[4].isNotBlank(), vision = LlamaNative.hasMedia(h), fileBytes = files.model.length())
        Loaded(h, caps.vision, System.currentTimeMillis(), files.model.parentFile!!, caps).also { loaded[role] = it; lastCaps[role] = caps }
    }

    /** Inference threads run at background priority so taps, scrolling and System UI always win. */
    private fun background() = android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

    /**
     * [background] runs on a second context over the same weights, so memory extraction and
     * other side jobs never evict the main conversation's cache.
     */
    suspend fun complete(role: ModelRole, messages: List<JsonObject>, tools: JsonArray?, media: String?, onText: ((String) -> Unit)?,
                         background: Boolean = false, maxTokens: Int = 512): ChatReply =
        (if (background) bgLocks else locks).getValue(role).withLock { compute.withLock {
            require(role != ModelRole.DECISION) { "d1 is a decision/classifier model and can't write text — use Text or Omni as the lead" }
            val e = engine(role)
            val handle = if (!background) e.handle else {
                if (e.bg == 0L) e.bg = withContext(pool) { background(); LlamaNative.fork(e.handle, 2048, 2) }
                e.bg
            }
            val useMedia = media?.takeIf { e.media && !background }
            val marker = if (useMedia != null) LlamaNative.marker() else null
            // LFM models use their native tool format; every other model gets its own chat template + a generic tool protocol.
            val p = if (e.caps.isLfm) LfmPrompt.render(messages, tools, marker) else GenericPrompt.render(handle, messages, tools, marker)
            // One saved checkpoint per distinct system prompt (each router tool set has its own).
            val key = Integer.toHexString(p.text.toByteArray().copyOf(p.stableBytes).contentHashCode())
            val cpFile = java.io.File(e.dir, "checkpoint-$key.bin")
            if (!background && p.stableBytes > 0 && e.cpKey != key) {
                withContext(pool) { LlamaNative.loadCheckpoint(handle, cpFile.path) }
                e.cpKey = key
            }
            active = active + role
            try {
                val buf = ByteArrayOutputStream()
                var lastPush = 0L
                val started = System.currentTimeMillis()
                val bytes = withContext(pool) {
                    background()
                    LlamaNative.generate(handle, p.text, useMedia, maxTokens, { piece ->
                        buf.write(piece)
                        val now = System.currentTimeMillis()
                        if (now - started > 180_000) return@generate false // hard stop: a reply never runs past 3 minutes
                        if (onText != null && now - lastPush > 120) { lastPush = now; onText(buf.toString(Charsets.UTF_8.name())) }
                        true
                    }, if (background) 0 else p.stableBytes)
                }
                if (!background) withContext(pool) {
                    if (LlamaNative.saveCheckpoint(handle, cpFile.path)) // keep the 6 most recent tool-set checkpoints
                        e.dir.listFiles { f -> f.name.startsWith("checkpoint") }.orEmpty().sortedByDescending { it.lastModified() }.drop(6).forEach { it.delete() }
                }
                LfmPrompt.parse(String(bytes, Charsets.UTF_8))
            } finally { active = active - role }
        } }

    fun unloadAll() { loaded.keys.toList().filter { !locks.getValue(it).isLocked }.forEach(::freeLoaded) }

    /** Capabilities of models loaded this session (shown in the Models screen). */
    val lastCaps = java.util.concurrent.ConcurrentHashMap<ModelRole, Capabilities>()

    /** Load (if needed) and report capabilities — the "probe" for a newly installed model. */
    suspend fun probe(role: ModelRole): Capabilities = locks.getValue(role).withLock { engine(role).caps }

    /** Generates a fixed short reply and measures load + decode speed on this phone. */
    suspend fun benchmark(role: ModelRole): String {
        val t0 = System.currentTimeMillis()
        probe(role)
        val tLoad = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        val r = complete(role, listOf(com.syntra.os.core.message("user", "Count from 1 to 40, separated by spaces.")), null, null, null, maxTokens = 96)
        val secs = ((System.currentTimeMillis() - t1) / 1000.0).coerceAtLeast(0.1)
        // ~4 characters per token for English/digits; good enough to compare models on the same phone.
        return "load ${tLoad} ms · ~${"%.1f".format(r.content.length / 4.0 / secs)} tokens/s (${"%.1f".format(secs)} s incl. prompt)"
    }

    fun hasProjector(role: ModelRole) = models.installed(role)?.mmproj != null

    /** Scores [labels] with a classifier model (d1) — reads one distribution, generates nothing. */
    suspend fun classify(role: ModelRole, prompt: String, labels: Array<String>): FloatArray = locks.getValue(role).withLock { compute.withLock {
        val e = engine(role)
        active = active + role
        try { withContext(pool) { background(); LlamaNative.scoreLabels(e.handle, prompt, labels) } } finally { active = active - role }
    } }

    fun stopAll() = loaded.values.toList().forEach { LlamaNative.stop(it.handle); if (it.bg != 0L) LlamaNative.stop(it.bg) }
    fun isLoaded(role: ModelRole) = loaded.containsKey(role)
}
