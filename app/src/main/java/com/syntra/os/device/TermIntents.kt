package com.syntra.os.device

/**
 * Plain-language terminal requests that map to one exact command, so they work instantly and never depend on a
 * small model guessing: "install hermes", "install ffmpeg", "run hermes agent summarise x", "update packages".
 * Returns null when the request needs real planning (then the AI agent handles it).
 */
object TermIntents {
    private val PKG = Regex("[a-z0-9][a-z0-9.+-]*")
    private val SHELL_START = setOf("apt", "apt-get", "pip", "pip3", "python", "python3", "git", "ls", "cd", "cat", "mkdir", "rm", "cp", "mv",
        "hermes", "aider", "node", "npm", "npx", "curl", "wget", "ffmpeg", "nano", "echo", "pwd", "df", "du", "top", "uname", "which", "bash", "sh")

    fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun match(input: String): String? {
        val t = input.trim()
        val low = t.lowercase().removePrefix("please ").trim()
        // Already a command ("pip install x", "git clone …"): run it as typed.
        if (low.substringBefore(' ') in SHELL_START) return t
        installerFor(low)?.let { return it }
        Regex("^(?:install|setup|set up)\\s+(.+)$").find(low)?.let { m ->
            val names = m.groupValues[1].split(Regex("[\\s,]+|\\band\\b")).map { it.trim() }.filter { it.isNotEmpty() && it != "the" && it != "package" && it != "packages" }
            if (names.isNotEmpty() && names.all { PKG.matches(it) }) return "apt-get update && apt-get install -y " + names.joinToString(" ")
        }
        Regex("^(?:run|start|open|use|ask)\\s+hermes(?:\\s+agent)?(?:\\s*(?:to|:|,)?\\s*(.*))?$").find(low)?.let { m ->
            val task = t.substring(t.length - m.groupValues[1].length).trim().ifBlank { "Say hello and list what you can do." }
            return "command -v hermes >/dev/null || { echo 'Hermes is not installed yet. Type: install hermes'; exit 1; }; hermes -q ${shQuote(task)} -Q"
        }
        if (low in setOf("update", "update packages", "upgrade", "update system")) return "apt-get update && apt-get upgrade -y"
        if (low.startsWith("python version") || low == "check python") return "python3 --version"
        return null
    }

    private fun installerFor(low: String): String? {
        if (!Regex("^(install|setup|set up)\\b").containsMatchIn(low)) return null
        val key = when {
            "hermes" in low -> "Hermes Agent"
            "aider" in low -> "Aider (coding agent)"
            "scrapling" in low -> "Scrapling"
            "claude" in low -> "Claude Code"
            "node" in low -> "Node.js"
            Regex("\\bpython\\b").containsMatchIn(low) -> "Python & tools"
            else -> return null
        }
        return Linux.INSTALLERS[key]
    }
}
