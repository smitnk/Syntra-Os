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

    // LFM2/LFM2.5 native: <|tool_call_start|>[web_search(query="x"), open_app(name='YouTube')]<|tool_call_end|>
    private val PY_CALLS = Regex("<\\|tool_call_start\\|>\\s*\\[(.*?)]\\s*<\\|tool_call_end\\|>", RegexOption.DOT_MATCHES_ALL)

    /** `a(x="1, 2", n=3), b()` → [(a, {x, n}), (b, {})]. Quotes may be ' or "; commas inside quotes are kept. */
    fun pythonicCalls(s: String): List<Pair<String, JsonObject>> {
        val out = mutableListOf<Pair<String, JsonObject>>()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf('(', i).takeIf { it >= 0 } ?: break
            val name = s.substring(i, open).trim().trimStart(',').trim()
            val args = mutableMapOf<String, JsonElement>()
            var j = open + 1; var key = StringBuilder(); var v = StringBuilder(); var inKey = true; var q: Char? = null
            fun flush() { if (key.isNotBlank()) args[key.toString().trim()] = if (v.startsWith("\u0000")) JsonPrimitive(v.substring(1)) else value(v.toString()); key = StringBuilder(); v = StringBuilder(); inKey = true }
            while (j < s.length) {
                val c = s[j]
                if (q != null) { if (c == '\\' && j + 1 < s.length) { v.append(s[j + 1]); j += 2; continue }; if (c == q) q = null else v.append(c) }
                else when {
                    c == ')' -> { flush(); break }
                    c == ',' -> flush()
                    c == '=' && inKey -> inKey = false
                    (c == '"' || c == '\'') && !inKey -> { q = c; v.append('\u0000') }
                    inKey -> key.append(c)
                    else -> v.append(c)
                }
                j++
            }
            if (name.isNotBlank()) out += name to JsonObject(args)
            i = j + 1
        }
        return out
    }

    fun parse(raw: String): ChatReply {
        val text = THINK.replace(raw, "")
        val calls = mutableListOf<ToolCall>()
        var visible = text
        PY_CALLS.findAll(text).forEach { m -> pythonicCalls(m.groupValues[1]).forEach { (n, a) -> calls += ToolCall("local${calls.size}", n, a.toString()) } }
        visible = PY_CALLS.replace(visible, "")
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
/** What one on-device call cost: shown under specialist steps so swaps on 8 GB are visible. */
data class CallMetric(val role: ModelRole, val loadMs: Long, val genMs: Long, val chars: Int, val freeMb: Long) {
    fun describe() = buildString {
        append(role.label); if (loadMs > 0) append(" loaded in ${"%.1f".format(loadMs / 1000.0)} s ·")
        append(" answered in ${"%.1f".format(genMs / 1000.0)} s")
        append(" · ${"%.1f".format(freeMb / 1024.0)} GB RAM free")
    }
}

/** Models at or below this size (≈ a 350M–700M 4-bit model) are "small": the router can stay loaded next to the main model. */
const val SMALL = 800_000_000L

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
    @Volatile var lastMetric: CallMetric? = null; private set
    private var lastLoadMs = 0L

    fun installedRoles() = ModelRole.entries.filter { models.installed(it) != null }

    private fun memInfo() = android.app.ActivityManager.MemoryInfo().also {
        ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
    }

    private fun freeLoaded(r: ModelRole) { loaded.remove(r)?.let { l -> if (l.bg != 0L) LlamaNative.free(l.bg); LlamaNative.free(l.handle) } }

    private suspend fun engine(role: ModelRole): Loaded = loadLock.withLock {
        loaded[role]?.let { it.lastUse = System.currentTimeMillis(); lastLoadMs = 0; return it }
        val t0 = System.currentTimeMillis()
        val files = models.installed(role) ?: error("${role.label} model not downloaded — open Setup → On-device models")
        val need = files.model.length() + 400_000_000L // weights + KV/compute buffers (projector loads later, only for images)
        // Phones under ~11 GB keep one big model in RAM; bigger phones two. A small router (<1 GB) never counts or gets evicted for one.
        val maxResident = if (memInfo().totalMem > 11_000_000_000L) 2 else 1
        fun big() = loaded.values.filter { it.caps.fileBytes > SMALL }
        val isBig = files.model.length() > SMALL
        while ((isBig && big().size >= maxResident) || (loaded.isNotEmpty() && memInfo().availMem - need < 1_500_000_000L)) {
            val victim = loaded.entries.filter { !locks.getValue(it.key).isLocked && !bgLocks.getValue(it.key).isLocked && (it.value.caps.fileBytes > SMALL || !isBig) }
                .minByOrNull { it.value.lastUse }?.key ?: break
            freeLoaded(victim)
        }
        // The RAG embedder (~330 MB) is the cheapest thing to drop before a chat model loads on a tight phone.
        if (memInfo().availMem - need < 1_500_000_000L) com.syntra.os.rag.Knowledge.releaseEmbedder()
        // No hard refusal: Android's "available" excludes cached apps it will reclaim on demand (v0.7 refused wrongly).
        // The low-memory killer handles real pressure; inference runs at background priority so System UI stays safe.
        // Phone CPUs are big.LITTLE (e.g. Dimensity 7200: 2 fast + 6 slow cores). Token-by-token writing is memory-bound and runs
        // at the speed of the slowest thread, so 2 threads (the fast cores) beat 4; reading the prompt is compute-bound and uses 4.
        val cores = Runtime.getRuntime().availableProcessors()
        val gen = 2; val batch = if (cores >= 8) 4 else 2
        val small = files.model.length() <= SMALL
        val h = withContext(pool) { background(); LlamaNative.load(files.model.path, null, if (small) 1024 else 4096, if (small) 1 else gen, if (small) 2 else batch, gpuLayers()) }
        val info = LlamaNative.info(h)
        val caps = Capabilities(arch = info[0], contextTrain = info[1].toIntOrNull() ?: 0, params = info[2].toLongOrNull() ?: 0,
            description = info[3], hasTemplate = info[4].isNotBlank(), vision = files.mmproj != null, fileBytes = files.model.length())
        lastLoadMs = System.currentTimeMillis() - t0
        Loaded(h, caps.vision, System.currentTimeMillis(), files.model.parentFile!!, caps).also { loaded[role] = it; lastCaps[role] = caps }
    }

    /**
     * Refuses to start a generation that would freeze the phone: very low RAM, or the phone already throttling from heat.
     * Better a clear message than System UI stalls (users had to kill other apps).
     */
    private fun guardResources(keep: ModelRole) {
        val m = memInfo()
        if (m.lowMemory || m.availMem < 500_000_000L) {
            unloadIdle(keep)
            check(memInfo().availMem >= 400_000_000L) { "Phone memory is almost full (${m.availMem / 1_048_576} MB free). Close a few apps and try again." }
        }
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE)
            error("The phone is too hot to run the model right now. Let it cool for a minute.")
    }

    /** Frees models nobody is using (e.g. the router after a while), plus the RAG embedder. */
    private fun unloadIdle(keep: ModelRole) {
        com.syntra.os.rag.Knowledge.releaseEmbedder()
        loaded.keys.filter { it != keep && !locks.getValue(it).isLocked && !bgLocks.getValue(it).isLocked }.forEach(::freeLoaded)
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
            guardResources(role)
            val useMedia = media?.takeIf { e.media && !background }
            if (useMedia != null && !LlamaNative.hasMedia(handle)) withContext(pool) { // first image: load the projector now
                models.installed(role)?.mmproj?.let { LlamaNative.attachMedia(handle, it.path, 4, gpuLayers()) }
            }
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
                lastMetric = CallMetric(role, lastLoadMs, System.currentTimeMillis() - started, bytes.size, memInfo().availMem / 1_048_576)
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
