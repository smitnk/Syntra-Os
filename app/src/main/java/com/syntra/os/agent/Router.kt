package com.syntra.os.agent

/**
 * Task router: picks which tool groups a request needs, so the model reads ~5 tool descriptions
 * instead of ~30 (most of the "thinking" delay on a phone is reading the prompt).
 * Deterministic keywords first; the d1 classifier is only consulted when keywords find nothing.
 */
enum class Route(val tools: Set<String>) {
    CHAT(setOf()),
    WEB(setOf("web_search", "web_fetch", "scrape", "save_output", "make_document")),
    PHONE(setOf("phone_screen", "phone_tap", "phone_type", "phone_swipe", "phone_action", "open_app", "list_apps", "read_notifications")),
    CODE(setOf("media_edit", "make_document", "shell", "file_list", "file_read", "file_write", "code_agent", "claude_code", "hermes_agent", "todo")),
    AUTOMATION(setOf("schedule_task", "list_schedules", "cancel_schedule", "http_request", "mcp_list_tools", "mcp_call")),
    GITHUB(setOf("github")),
    TEAM(setOf("ask_specialist", "consult_team")),
}

/** Always available: memory and learning are cheap and useful in every task. */
val CORE_TOOLS = setOf("memory_search", "memory_remember", "learn", "knowledge_search", "knowledge_add")

object Router {
    private val keywords: Map<Route, List<String>> = mapOf(
        Route.WEB to listOf("news", "search", "google", "latest", "today", "price", "weather", "who is", "what is", "website", "web",
            "research", "find out", "look up", "scrape", "article", "khabar", "samachar", "batao", "kya hai", "war", "election", "score"),
        Route.PHONE to listOf("open ", "screen", "tap", "click", "type ", "swipe", "scroll", "notification", "app ", "apps", "whatsapp",
            "youtube", "instagram", "settings", "call ", "message", "kholo", "chalu", "band karo"),
        Route.CODE to listOf("video", "audio", "ffmpeg", "trim", "mp4", "mp3", "pdf", "docx", "word file", "document", "convert", "code", "script", "python", "program", "build", "app ", "website", "compile", "run ", "install", "terminal",
            "linux", "shell", "file", "folder", "bug", "error", "git", "npm", "pip", "hermes", "aider"),
        Route.AUTOMATION to listOf("every ", "remind", "schedule", "tomorrow", "daily", "morning", "later", "zapier", "webhook", "post to",
            "upload", "email", "gmail", "sheet", "automate", "roz", "har din"),
        Route.GITHUB to listOf("github", "repo", "pull request", "issue", "commit"),
    )

    fun keywordRoutes(text: String): Set<Route> {
        val t = " " + text.lowercase() + " "
        return keywords.filterValues { ks -> ks.any { it in t } }.keys
    }

    /** Labels the d1 classifier scores (single words so their first tokens differ). */
    val labels = arrayOf("chat", "web", "phone", "code", "automation")
    fun fromLabel(l: String) = when (l) { "web" -> Route.WEB; "phone" -> Route.PHONE; "code" -> Route.CODE; "automation" -> Route.AUTOMATION; else -> Route.CHAT }

    /** ChatML (LFM2/Qwen family). chat = talk or explain; web = needs current facts; phone = control apps; code = files/Linux/media; automation = schedules/apps. */
    fun d1Prompt(text: String) = "<|im_start|>system\nClassify the user's task. chat = conversation, explanation, writing; web = needs internet facts or news; " +
        "phone = open or control phone apps; code = files, terminal, programming, video/audio/PDF; automation = reminders, schedules, email.<|im_end|>\n" +
        "<|im_start|>user\n$text<|im_end|>\n<|im_start|>assistant\nCategory:"

    /** Tool names for the chosen routes (MCP tools ride along with AUTOMATION). */
    fun toolNames(routes: Set<Route>, code: Boolean): Set<String> =
        CORE_TOOLS + routes.flatMap { it.tools } + (if (code) Route.CODE.tools else emptySet())
}
