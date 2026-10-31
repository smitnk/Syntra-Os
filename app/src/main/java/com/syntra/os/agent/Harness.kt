package com.syntra.os.agent

import com.syntra.os.core.json
import com.syntra.os.core.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Harness pieces around the agent loop: resumable checkpoints, result verification,
 * risk levels for approvals, and retry policy. Kept free of Android UI so it is unit-testable.
 */

/** Everything needed to rebuild a run after a crash, kill or model swap. The model's KV cache is never the source of truth. */
@Serializable
data class TaskCheckpoint(
    val id: String,
    val goal: String,
    val messages: List<JsonObject>,
    val step: Int,
    val code: Boolean = false,
    val skillName: String? = null,
    val toolNames: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

class CheckpointStore(private val dir: File) {
    init { dir.mkdirs() }
    private fun f(id: String) = File(dir, "$id.json")

    @Synchronized fun save(c: TaskCheckpoint) {
        val tmp = File(dir, "${c.id}.json.tmp")
        tmp.writeText(json.encodeToString(TaskCheckpoint.serializer(), c))
        tmp.renameTo(f(c.id)) // atomic: a crash mid-write never leaves half a checkpoint
    }

    fun load(id: String): TaskCheckpoint? = runCatching { json.decodeFromString(TaskCheckpoint.serializer(), f(id).readText()) }.getOrNull()
    fun delete(id: String) { f(id).delete() }
    fun all(): List<TaskCheckpoint> = dir.listFiles { x -> x.name.endsWith(".json") }.orEmpty().mapNotNull { load(it.nameWithoutExtension) }
}

/** How much damage a tool call could do; shown on the approval card. */
enum class Risk(val label: String) {
    READ("Reads data"), LOCAL_WRITE("Changes files in Syntra"), NETWORK("Sends data to the internet"),
    DEVICE("Controls your phone"), CODE("Runs code in Linux"), EXTERNAL("Acts on an online account"),
}

fun riskOf(name: String): Risk = when {
    name in PHONE_TOOLS -> Risk.DEVICE
    name in LINUX_TOOLS || name in com.syntra.os.ext.ExtRuntime.tools -> Risk.CODE
    name.startsWith("mcp__") || name == "mcp_call" || name == "delegate" -> Risk.EXTERNAL
    name == "http_request" || name == "github" -> Risk.NETWORK
    name in setOf("file_write", "save_output", "memory_remember", "memory_link", "knowledge_add", "review_page", "learn", "schedule_task", "cancel_schedule") -> Risk.LOCAL_WRITE
    else -> Risk.READ
}

/** Read-only tools may be retried once after a transient failure (network blips, timeouts). */
val RETRYABLE = setOf("web_search", "web_fetch", "scrape", "memory_search", "knowledge_search", "phone_screen", "list_apps", "read_notifications", "file_read", "file_list")

/** A tool's claim checked against reality. [artifact] is a real file the user can open. */
data class Verification(val ok: Boolean, val note: String = "", val artifact: File? = null)

object Verifier {
    /**
     * Checks a tool result. [workspace] and [agenticOs] are the app folders tools write into.
     * Unknown tools are treated as verified only when they didn't report an error.
     */
    fun check(name: String, args: JsonObject, result: String, workspace: File, agenticOs: File): Verification {
        if (result.startsWith("Error:") || result == "User denied this action.") return Verification(false, result.take(200))
        return when (name) {
            "file_write" -> {
                val f = File(workspace, args.str("path").orEmpty()).canonicalFile
                val expected = args.str("content").orEmpty().length
                if (f.isFile && f.length() >= expected.coerceAtMost(1)) Verification(true, "${f.name}: ${f.length()} bytes", f)
                else Verification(false, "file ${args.str("path")} missing after write")
            }
            "save_output" -> {
                val rel = result.removePrefix("Saved ").trim()
                val f = File(agenticOs, rel)
                if (f.isFile && f.length() > 0) Verification(true, rel, f) else Verification(false, "output file missing")
            }
            "shell", "code_agent", "claude_code", "hermes_agent" -> {
                val code = Regex("^exit (-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
                if (code == null || code == 0) Verification(true) else Verification(false, "exit code $code")
            }
            "http_request", "github" -> {
                val code = Regex("^HTTP (\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
                if (code != null && code in 200..299) Verification(true) else Verification(false, "HTTP $code")
            }
            "review_page" -> if (!result.startsWith("Saved ")) Verification(true, "declined") else {
                val f = File(workspace, result.removePrefix("Saved ").substringBefore(" (").trim())
                if (f.isFile && f.length() > 0) Verification(true, f.name, f) else Verification(false, "page missing after save")
            }
            "web_search" -> if (result == "No results.") Verification(false, "no search results") else Verification(true)
            else -> {
                // Extension tools run in Linux and report "exit N" first.
                val code = Regex("^exit (-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
                if (code == null || code == 0) Verification(true) else Verification(false, "exit code $code")
            }
        }
    }
}

/** Final outcome of a run, shown with the answer. */
data class RunReport(val verified: Boolean, val artifacts: List<File>, val failures: List<String>)

/** Rough token estimate (≈4 chars/token) used to keep prompts inside the model's context. */
fun estTokens(messages: List<JsonObject>) = messages.sumOf { it.toString().length } / 4

/**
 * Shrinks old tool results first (they're the bulk), keeping the system prompt, the goal and the latest turns intact.
 * Full outputs stay in the task log; the model gets a pointer.
 */
fun trimToBudget(messages: MutableList<JsonObject>, budgetTokens: Int) {
    var i = 2
    while (estTokens(messages) > budgetTokens && i < messages.size - 3) {
        val m = messages[i]
        if (m.str("role") == "tool" && (m.str("content")?.length ?: 0) > 300)
            messages[i] = com.syntra.os.core.toolResult(m.str("tool_call_id") ?: "t", m.str("content")!!.take(240) + "… [trimmed; full output in task log]")
        i++
    }
}
