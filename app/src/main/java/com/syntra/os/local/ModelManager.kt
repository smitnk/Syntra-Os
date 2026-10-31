package com.syntra.os.local

import com.syntra.os.core.ModelRole
import com.syntra.os.core.http
import com.syntra.os.core.httpCall
import com.syntra.os.core.json
import com.syntra.os.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

/** LFM3 GGUF repos on Hugging Face, one per role. */
val CATALOG: Map<ModelRole, String> = mapOf(
    ModelRole.TEXT to "LiquidAI/LFM3-1.7B-GGUF",
    ModelRole.VISION to "LiquidAI/LFM3-VL-2B-GGUF",
    ModelRole.AUDIO to "LiquidAI/LFM3-Audio-2B-GGUF",
    ModelRole.OMNI to "LiquidAI/LFM3-Omni-2B-GGUF",
    ModelRole.DECISION to "LiquidAI/d1-Omni-2B-GGUF",
)

/** Multilingual embedding model for the knowledge base (RAG). */
const val EMBED_REPO = "ggml-org/embeddinggemma-300M-GGUF"

class ModelFiles(val model: File, val mmproj: File?)

/** Picks the phone-friendly quant from a repo's file list: Q4_0 first, then Q4_K_M. */
fun pickFiles(names: List<String>): Pair<String, String?>? {
    val ggufs = names.filter { it.endsWith(".gguf") && '/' !in it }
    val (proj, main) = ggufs.partition { "mmproj" in it.lowercase() }
    val m = listOf("Q4_0", "Q4_K_M", "Q5_K_M", "Q8_0").firstNotNullOfOrNull { q -> main.firstOrNull { q in it } } ?: main.firstOrNull() ?: return null
    val p = listOf("Q8_0", "F16").firstNotNullOfOrNull { q -> proj.firstOrNull { q in it } } ?: proj.firstOrNull()
    return m to p
}

class ModelManager(private val root: File) {
    private fun dir(role: ModelRole) = File(root, "models/${role.name.lowercase()}").apply { mkdirs() }

    /** role → custom model id: lets any installed GGUF take a role (e.g. the main model). */
    @Volatile var overrides: Map<ModelRole, String> = emptyMap()

    private fun customDir(id: String) = File(root, "models/custom/$id")
    fun customModels(): List<String> = File(root, "models/custom").listFiles().orEmpty()
        .filter { d -> d.listFiles().orEmpty().any { it.name.endsWith(".gguf") } }.map { it.name }.sorted()
    fun deleteCustom(id: String) { customDir(id).deleteRecursively() }

    /** Any Hugging Face GGUF repo: picks the phone-friendly quant (+ projector if present). */
    suspend fun downloadCustom(repo: String, onProgress: (String) -> Unit): String {
        val r = repo.trim().removePrefix("https://huggingface.co/").trim('/')
        require(Regex("[\\w.-]+/[\\w.-]+").matches(r)) { "Use owner/repo, e.g. unsloth/Qwen3-1.7B-GGUF" }
        val resp = httpCall("https://huggingface.co/api/models/$r")
        if (resp.code != 200) error("Hugging Face HTTP ${resp.code} for $r")
        val names = (json.parseToJsonElement(resp.body).jsonObject["siblings"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonObject.str("rfilename") }
        val (main, proj) = pickFiles(names) ?: error("no .gguf file in $r")
        val id = main.removeSuffix(".gguf").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val d = customDir(id).apply { mkdirs() }
        listOfNotNull(main, proj).forEach { fetch(r, it, File(d, it), onProgress) }
        return id
    }

    fun installed(role: ModelRole): ModelFiles? {
        overrides[role]?.let { id -> filesIn(customDir(id))?.let { return it } }
        return filesIn(dir(role))
    }

    private fun filesIn(d: File): ModelFiles? {
        val files = d.listFiles().orEmpty().filter { it.name.endsWith(".gguf") }
        val main = files.firstOrNull { "mmproj" !in it.name.lowercase() } ?: return null
        return ModelFiles(main, files.firstOrNull { "mmproj" in it.name.lowercase() })
    }

    private val embedDir get() = File(root, "models/embed").apply { mkdirs() }
    fun embedder(): File? = embedDir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".gguf") }
    fun deleteEmbedder() { embedDir.deleteRecursively() }
    suspend fun downloadEmbedder(onProgress: (String) -> Unit): File {
        val r = httpCall("https://huggingface.co/api/models/$EMBED_REPO")
        if (r.code != 200) error("Hugging Face HTTP ${r.code} for $EMBED_REPO")
        val names = (json.parseToJsonElement(r.body).jsonObject["siblings"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.str("rfilename") }
        val (main, _) = pickFiles(names) ?: error("no GGUF in $EMBED_REPO")
        return File(embedDir, main).also { fetch(EMBED_REPO, main, it, onProgress) }
    }

    fun delete(role: ModelRole) { dir(role).deleteRecursively() }

    suspend fun download(role: ModelRole, onProgress: (String) -> Unit) {
        val repo = CATALOG.getValue(role)
        val r = httpCall("https://huggingface.co/api/models/$repo")
        if (r.code == 404 || r.code == 401) error("$repo is not published on Hugging Face yet")
        if (r.code != 200) error("Hugging Face HTTP ${r.code}")
        val names = (json.parseToJsonElement(r.body).jsonObject["siblings"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonObject.str("rfilename") }
        val (main, proj) = pickFiles(names) ?: error("no GGUF file in $repo")
        listOfNotNull(main, proj).forEach { name -> fetch(repo, name, File(dir(role), name), onProgress) }
    }

    private suspend fun fetch(repo: String, name: String, dest: File, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (dest.exists()) return@withContext
        val part = File(dest.path + ".part")
        val req = Request.Builder().url("https://huggingface.co/$repo/resolve/main/$name").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download $name: HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 8_000_000) {
                            last = done
                            onProgress(if (total > 0) "$name ${done * 100 / total}%" else "$name ${done / 1_000_000} MB")
                        }
                    }
                }
            }
        }
        part.renameTo(dest) // only complete files get the .gguf name
    }
}
