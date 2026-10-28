package com.syntra.os.device

import android.content.Context
import com.syntra.os.core.http
import com.syntra.os.core.httpCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ubuntu inside the app via proot (no Termux, no root) — the approach UserLAnd uses.
 * proot + its loader ship as lib*.so in nativeLibraryDir, the only place Android 10+ lets an app execute files.
 */
object Linux {
    private const val BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    private fun lib(ctx: Context, n: String) = File(ctx.applicationInfo.nativeLibraryDir, n)
    fun rootfs(ctx: Context) = File(ctx.filesDir, "ubuntu")
    fun bundled(ctx: Context) = lib(ctx, "libproot.so").exists()
    fun installed(ctx: Context) = File(rootfs(ctx), "usr/bin/bash").exists()

    suspend fun install(ctx: Context, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(bundled(ctx)) { "proot is not bundled in this build" }
        progress("Finding Ubuntu 24.04 image…")
        val index = httpCall(BASE)
        if (index.code != 200) error("Ubuntu mirror HTTP ${index.code}")
        val name = Regex("ubuntu-base-24\\.04(\\.\\d+)?-base-arm64\\.tar\\.gz").findAll(index.body).map { it.value }.distinct()
            .maxByOrNull { it } ?: error("no arm64 image listed")
        val tgz = File(ctx.cacheDir, name)
        if (!tgz.exists()) {
            val part = File(tgz.path + ".part")
            http.newCall(Request.Builder().url(BASE + name).build()).execute().use { r ->
                if (!r.isSuccessful) error("download HTTP ${r.code}")
                val total = r.body!!.contentLength()
                r.body!!.byteStream().use { inp -> part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16); var done = 0L; var last = 0L
                    while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n
                        if (done - last > 2_000_000) { last = done; progress("Downloading Ubuntu ${if (total > 0) done * 100 / total else done / 1_000_000}${if (total > 0) "%" else " MB"}") } }
                } }
            }
            part.renameTo(tgz)
        }
        progress("Unpacking…")
        val root = rootfs(ctx).apply { deleteRecursively(); mkdirs() }
        // Android's toybox tar; ownership errors are expected (no root) and harmless under proot -0.
        val p = ProcessBuilder("/system/bin/tar", "-xzf", tgz.path, "-C", root.path).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (!installed(ctx)) error("unpack failed: ${log.takeLast(300)}")
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/apt/apt.conf.d/99syntra").writeText("APT::Sandbox::User \"root\";\n") // no _apt user switching under proot
        File(root, "root").mkdirs()
        tgz.delete()
        progress("Ubuntu ready. Try: apt update && apt install -y python3")
    }

    /**
     * Points everything inside Ubuntu at Syntra's local API (on-device LFM, or cloud via "provider/model"):
     * OpenAI-style env vars, an `ai` command, and Hermes' config.
     */
    fun configure(ctx: Context, apiKey: String) {
        val root = rootfs(ctx)
        if (!installed(ctx)) return
        val base = "http://127.0.0.1:${com.syntra.os.local.LocalServer.PORT}/v1"
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/syntra.sh").writeText("""
            export OPENAI_BASE_URL=$base
            export OPENAI_API_BASE=$base
            export OPENAI_API_KEY=$apiKey
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
        """.trimIndent() + "\n")
        File(root, "usr/local/bin/ai").apply {
            writeText(AI_SCRIPT); setExecutable(true, false)
        }
        File(root, "root/.hermes").mkdirs()
        File(root, "root/.hermes/config.yaml").takeIf { !it.exists() || it.readText().contains("# syntra-managed") }?.writeText("""
            # syntra-managed: Hermes uses Syntra's on-device model. Delete this line to manage the file yourself.
            model:
              default: "syntra"
              provider: "custom"
              base_url: "$base"
              api_key: "$apiKey"
        """.trimIndent() + "\n")
    }

    /** `ai "question"` — or pipe text in: `cat main.py | ai "explain"`. `AI_MODEL=openrouter/<model> ai …` uses the cloud. */
    private val AI_SCRIPT = """
        #!/usr/bin/env python3
        import json, os, sys, urllib.request
        prompt = " ".join(sys.argv[1:])
        if not sys.stdin.isatty():
            data = sys.stdin.read()
            if data.strip(): prompt = (prompt + "\n\n" + data).strip()
        if not prompt: print("usage: ai <question>   (or: cat file | ai <question>)"); sys.exit(1)
        body = json.dumps({"model": os.environ.get("AI_MODEL", "syntra"), "messages": [{"role": "user", "content": prompt}]}).encode()
        req = urllib.request.Request(os.environ["OPENAI_BASE_URL"] + "/chat/completions", body,
            {"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["OPENAI_API_KEY"]})
        print(json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"])
    """.trimIndent() + "\n"

    /** One-tap installers shown in the Linux tab; each runs in the visible shell. */
    val INSTALLERS = linkedMapOf(
        "Python & tools" to "apt update && apt install -y python3 python3-pip python3-venv git curl ca-certificates nano",
        "Hermes Agent" to "apt update && apt install -y curl git ca-certificates xz-utils && curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash && echo 'Hermes ready: hermes -q \"task\" -Q'",
        "Aider (coding agent)" to "apt update && apt install -y python3 python3-pip git && pip3 install --break-system-packages aider-chat && echo 'Aider ready: aider --model openai/syntra'",
        "Scrapling" to "apt update && apt install -y python3 python3-pip && pip3 install --break-system-packages scrapling curl_cffi && echo 'Scrapling ready'",
        "Node.js" to "apt update && apt install -y nodejs npm",
    )

    /** proot command line; [shell] runs after it. */
    fun process(ctx: Context, shell: List<String>): ProcessBuilder {
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val pb = ProcessBuilder(listOf(lib(ctx, "libproot.so").path, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfs(ctx).path, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            // The agentic-OS folder (context, memory, outputs) is shared between the app and Ubuntu.
            "-b", File(ctx.filesDir, "agentic-os").apply { mkdirs() }.path + ":/root/agentic-os", "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") + shell)
        pb.environment().apply {
            put("PROOT_LOADER", lib(ctx, "libproot-loader.so").path)
            put("PROOT_TMP_DIR", tmp.path)
            put("LD_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir)
            // Seccomp acceleration makes proot exit silently on some Android kernels; plain ptrace is reliable.
            put("PROOT_NO_SECCOMP", "1")
        }
        return pb.redirectErrorStream(true)
    }

    /** One-shot command for the agent. */
    suspend fun run(ctx: Context, command: String, timeoutMs: Long = 180_000): String = withContext(Dispatchers.IO) {
        check(installed(ctx)) { "Ubuntu isn't installed yet (Linux tab → Install Ubuntu)." }
        val p = process(ctx, listOf("/bin/bash", "-lc", command)).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { if (out.length < 20_000) out.appendLine(it) } } }
        reader.start()
        val done = withTimeoutOrNull(timeoutMs) { while (p.isAlive) kotlinx.coroutines.delay(100); true }
        if (done == null) { p.destroyForcibly(); synchronized(out) { out.appendLine("[timed out]") } }
        reader.join(1000)
        "exit ${if (done == null) -1 else p.exitValue()}\n" + synchronized(out) { out.toString() }.takeLast(6000)
    }
}

/** A long-lived bash for the Linux tab, so `cd`, variables and background jobs persist. */
class LinuxSession(private val ctx: Context, private val onOutput: (String) -> Unit) {
    private var proc: Process? = null

    fun start() {
        if (proc?.isAlive == true) return
        val p = Linux.process(ctx, listOf("/bin/bash", "--login", "-i")).start()
        proc = p
        Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine(onOutput) }
            onOutput("[shell exited, code ${runCatching { p.waitFor() }.getOrDefault(-1)}]")
        }.start()
        send("export PS1='$ '; cd /root")
    }

    fun send(line: String) {
        val p = proc?.takeIf { it.isAlive } ?: return onOutput("[shell not running]")
        runCatching { p.outputStream.write((line + "\n").toByteArray()); p.outputStream.flush() }
    }

    fun stop() { proc?.destroy(); proc?.waitFor(2, TimeUnit.SECONDS); proc = null }
}
