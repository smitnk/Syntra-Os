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
        // Harness wrappers: run each coding agent on Syntra's models. MODEL can be "syntra" (on-device) or "<provider>/<model>" (cloud).
        val wrappers = mapOf(
            // Claude Code through Syntra's Anthropic-compatible endpoint. Plain `claude` still uses your own Anthropic login.
            "claude-local" to "#!/bin/sh\nexport ANTHROPIC_BASE_URL=http://127.0.0.1:${com.syntra.os.local.LocalServer.PORT}\n" +
                "export ANTHROPIC_AUTH_TOKEN=\"\$OPENAI_API_KEY\"\nexport ANTHROPIC_MODEL=\"\${MODEL:-syntra}\"\n" +
                "export ANTHROPIC_SMALL_FAST_MODEL=\"\${MODEL:-syntra}\"\nexec claude \"\$@\"\n",
            "aider-local" to "#!/bin/sh\nexec aider --model \"openai/\${MODEL:-syntra}\" --no-show-model-warnings --no-check-update \"\$@\"\n",
            // DeepSeek (or any cloud model) via Syntra's router; the key stays in Syntra, not in Linux.
            "aider-deepseek" to "#!/bin/sh\nexec aider --model openai/deepseek/deepseek-chat --no-show-model-warnings --no-check-update \"\$@\"\n",
        )
        wrappers.forEach { (n, body) -> File(root, "usr/local/bin/$n").apply { writeText(body); setExecutable(true, false) } }
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
        "Claude Code" to "apt update && apt install -y nodejs npm git && npm install -g @anthropic-ai/claude-code && " +
            "echo 'Claude Code ready: claude-local (Syntra models) or claude (your Anthropic account)'",
    )

    /** proot command line; [shell] runs after it. */
    /** A way of running proot. Which one works depends on the phone's kernel and Android seccomp policy. */
    enum class Variant(val bin: String, val loader: String, val termux: Boolean, val seccomp: Boolean) {
        SYNTRA("libproot.so", "libproot-loader.so", true, true),            // Termux source, built for Syntra
        SYNTRA_NO_SECCOMP("libproot.so", "libproot-loader.so", true, false),
        LEGACY("libproot-gg.so", "libproot-gg-loader.so", false, true),     // older Android-app build
        LEGACY_NO_SECCOMP("libproot-gg.so", "libproot-gg-loader.so", false, false),
    }

    @Volatile private var chosen: Variant? = null
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("linux", Context.MODE_PRIVATE)
    var lastProbe = ""; private set

    /**
     * Tries each variant on a command that exercises what broke before (cd, /proc/sys reads, apt's
     * libgcrypt fips check) and remembers the first that works.
     */
    fun variant(ctx: Context): Variant {
        chosen?.let { return it }
        prefs(ctx).getString("variant", null)?.let { n -> Variant.entries.firstOrNull { it.name == n }?.let { chosen = it; return it } }
        val log = StringBuilder()
        for (v in Variant.entries) {
            if (!lib(ctx, v.bin).exists()) continue
            val r = runCatching {
                val p = build(ctx, v, listOf("/bin/sh", "-c", "cd /root && cat /proc/sys/crypto/fips_enabled && echo SYNTRA_OK")).start()
                val out = StringBuffer()
                val t = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }.apply { start() }
                if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly() // a hung variant must not block the others
                t.join(2000); "exit ${runCatching { p.exitValue() }.getOrDefault(-1)} $out"
            }.getOrElse { "error: ${it.message}" }
            log.appendLine("${v.name}: ${r.trim().takeLast(160)}")
            if ("SYNTRA_OK" in r) { chosen = v; prefs(ctx).edit().putString("variant", v.name).apply(); lastProbe = log.toString(); return v }
        }
        lastProbe = log.toString()
        return Variant.SYNTRA_NO_SECCOMP.also { chosen = it } // nothing passed; keep the most compatible and show the log
    }

    fun resetVariant(ctx: Context) { chosen = null; prefs(ctx).edit().remove("variant").apply() }

    /** Fake files for /proc entries Android hides from apps (the same set proot-distro provides, plus fips_enabled). */
    private fun fakeProc(ctx: Context): List<String> {
        val dir = File(ctx.filesDir, "ubuntu-sys").apply { mkdirs() }
        val files = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "uptime" to "124.08 932.80\n",
            "version" to "Linux version 6.17.0-PRoot-Distro (proot@termux) #1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nintr 0\nctxt 0\nbtime 1700000000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n",
            "cap_last_cap" to "40\n", "max_user_watches" to "4096\n", "overflowuid" to "65534\n", "overflowgid" to "65534\n",
            "fips_enabled" to "0\n",
        )
        files.forEach { (n, c) -> File(dir, n).takeIf { !it.exists() }?.writeText(c) }
        val target = mapOf("loadavg" to "/proc/loadavg", "uptime" to "/proc/uptime", "version" to "/proc/version", "vmstat" to "/proc/vmstat",
            "stat" to "/proc/stat", "cap_last_cap" to "/proc/sys/kernel/cap_last_cap", "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
            "overflowuid" to "/proc/sys/kernel/overflowuid", "overflowgid" to "/proc/sys/kernel/overflowgid", "fips_enabled" to "/proc/sys/crypto/fips_enabled")
        return target.flatMap { (n, t) -> listOf("-b", "${File(dir, n).path}:$t") }
    }

    private fun build(ctx: Context, v: Variant, shell: List<String>): ProcessBuilder {
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val shm = File(ctx.cacheDir, "proot-shm").apply { mkdirs() }
        val args = mutableListOf(lib(ctx, v.bin).path, "-0", "--link2symlink")
        // proot-distro's Termux flags: kill children on exit, SysV IPC emulation, fake kernel release, lstat fix.
        if (v.termux) args += listOf("--kill-on-exit", "--sysvipc", "-L", "--kernel-release=6.17.0-PRoot-Distro")
        args += listOf("-r", rootfs(ctx).path, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random", "-b", "/proc/self/fd:/dev/fd", "-b", "${shm.path}:/dev/shm")
        args += fakeProc(ctx)
        // The agentic-OS folder (context, memory, outputs) is shared between the app and Ubuntu.
        args += listOf("-b", File(ctx.filesDir, "agentic-os").apply { mkdirs() }.path + ":/root/agentic-os",
            "-b", File(ctx.filesDir, "workspace").apply { mkdirs() }.path + ":/root/workspace", // media/doc tools share files with Linux "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        val pb = ProcessBuilder(args + shell)
        pb.environment().apply {
            put("PROOT_LOADER", lib(ctx, v.loader).path)
            put("PROOT_TMP_DIR", tmp.path)
            put("LD_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir)
            if (!v.seccomp) put("PROOT_NO_SECCOMP", "1") else remove("PROOT_NO_SECCOMP")
        }
        return pb.redirectErrorStream(true)
    }

    /** What's installed in Syntra's Linux, so the app and the agent can offer and use it. */
    fun installedTools(ctx: Context): List<String> {
        val r = rootfs(ctx)
        if (!installed(ctx)) return emptyList()
        fun has(vararg paths: String) = paths.any { File(r, it).exists() }
        return buildList {
            if (has("usr/bin/python3")) add("python3")
            if (has("usr/bin/git")) add("git")
            if (has("usr/bin/node")) add("node")
            if (has("root/.local/bin/hermes", "usr/local/bin/hermes")) add("hermes")
            if (has("usr/local/bin/aider", "root/.local/bin/aider")) add("aider")
            if (has("usr/local/bin/claude", "usr/bin/claude", "root/.local/bin/claude")) add("claude")
            if (File(r, "usr/local/lib").walkTopDown().maxDepth(4).any { it.name == "scrapling" }) add("scrapling")
        }
    }

    /** proot command line using the variant that works on this phone; [shell] runs inside Ubuntu. */
    fun process(ctx: Context, shell: List<String>): ProcessBuilder = build(ctx, variant(ctx), shell)

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
