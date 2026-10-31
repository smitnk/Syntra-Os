package com.syntra.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntra.os.AppViewModel
import com.syntra.os.core.McpServer
import com.syntra.os.core.ModelRole
import com.syntra.os.core.Provider
import com.syntra.os.core.StepKind
import com.syntra.os.core.TaskStatus
import com.syntra.os.mcp.McpClient
import kotlinx.coroutines.launch

@Composable
private fun Header(title: String, sub: String) = Column(Modifier.padding(bottom = 8.dp)) {
    Text(title, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
    Text(sub, color = Dim, fontSize = 13.sp)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) =
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Cyan, fontWeight = FontWeight.SemiBold); content()
        }
    }

@Composable
fun SkillsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("SKILLS", "Specialist roles injected into the agent. Installed skills stay off until you review them.") }
        item {
            Section("Install from GitHub") {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("https://github.com/msitarzewski/agency-agents/blob/main/…md") })
                OutlinedButton({ msg = "Installing…"; vm.installSkill(url) { msg = it; url = "" } }, enabled = url.isNotBlank()) { Text("Install") }
                if (msg.isNotBlank()) Text(msg, color = Dim, fontSize = 12.sp)
            }
        }
        items(vm.skills, key = { it.name + it.source }) { s ->
            Card(Modifier.fillMaxWidth().clickable { open = if (open == s.name) null else s.name }, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold)
                            Text(s.description, color = Dim, fontSize = 12.sp, maxLines = 2)
                        }
                        if (s.source != "built-in") IconButton({ vm.removeSkill(s) }) { Icon(Icons.Outlined.Delete, "Remove", tint = Dim) }
                        Switch(s.enabled, { vm.toggleSkill(s) })
                    }
                    if (open == s.name) {
                        Text(s.source, color = Violet, fontSize = 11.sp)
                        Text(s.prompt.take(4000), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    var mcpMsg by remember { mutableStateOf("") }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header("SETUP", "Models run on this phone by default. Cloud and agent providers are optional.") }

        item {
            val tick = vm.installedTick // re-read files after downloads
            Section("On-device models (LFM3 · llama.cpp)") {
                Text("Downloaded from Hugging Face once, then fully offline. ~1–2 GB each; Wi-Fi recommended.", color = Dim, fontSize = 12.sp)
                ModelRole.entries.forEach { r ->
                    val files = remember(tick, r) { vm.models.installed(r) }
                    val dl = vm.downloads[r]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.defaultModel, fontWeight = FontWeight.SemiBold)
                            Text(when {
                                dl != null -> dl
                                files != null -> "${r.label} · ${files.model.length() / 1_000_000} MB" + (if (files.mmproj != null) " + projector" else "") +
                                    (if (vm.engines.isLoaded(r)) " · loaded" else "")
                                else -> "${r.label} · not downloaded"
                            } + if (r == ModelRole.DECISION) " · classifier, not used for chat" else "", color = if (dl?.startsWith("Failed") == true) Danger else Dim, fontSize = 12.sp)
                        }
                        when {
                            dl != null -> {}
                            files != null -> TextButton({ vm.deleteModel(r) }) { Text("Delete", color = Dim) }
                            else -> OutlinedButton({ vm.downloadModel(r) }) { Text("Download") }
                        }
                    }
                }
            }
        }

        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Section("Phone control") {
                if (vm.isLite) Text("Lite edition: screen control and notification reading are not included, so it installs without " +
                    "the \"sensitive data\" warning. Install the Full APK to get them.", color = Dim, fontSize = 12.sp)
                else {
                    Text("Lets Syntra read the screen, tap, type, swipe, open apps and read notifications.", color = Dim, fontSize = 12.sp)
                    Text("If Android says \"Restricted setting\": App info → ⋮ → Allow restricted settings, then try again.", color = Dim, fontSize = 12.sp)
                    OutlinedButton({ ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text(if (com.syntra.os.device.SyntraAccessibility.instance != null) "Accessibility: on ✓" else "Enable accessibility service")
                    }
                    OutlinedButton({ ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                        Text(if (com.syntra.os.device.SyntraNotifications.instance != null) "Notification access: on ✓" else "Enable notification access")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask before tapping / typing", Modifier.weight(1f))
                    Switch(s.askPhoneActions, { vm.updateSettings(s.copy(askPhoneActions = it)) })
                }
                Text("Termux shell: install Termux, set allow-external-apps = true in ~/.termux/termux.properties, then grant the permission below.",
                    color = Dim, fontSize = 12.sp)
                val termuxPerm = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                OutlinedButton({ termuxPerm.launch("com.termux.permission.RUN_COMMAND") }) { Text("Grant Termux permission") }
            }
        }

        item {
            Section("Agentic OS") {
                Text("Syntra reads this on every task (context/identity.md). Corrections become rules in memory/learnings.md; " +
                    "skill results are saved in outputs/. The same folder is /root/agentic-os in Linux.", color = Dim, fontSize = 12.sp)
                var identity by remember { mutableStateOf(vm.agenticOs.identity.readText()) }
                OutlinedTextField(identity, { identity = it }, Modifier.fillMaxWidth(), label = { Text("Who you are, your work, goals, voice") }, minLines = 4, maxLines = 10)
                TextButton({ vm.agenticOs.identity.writeText(identity) }) { Text("Save identity") }
                val rules = remember(vm.tasks.size) { vm.agenticOs.learnings.readText().lines().filter { it.startsWith("- ") } }
                Text("Learned rules (${rules.size})", color = Violet, fontSize = 12.sp)
                rules.takeLast(8).forEach { Text(it, fontSize = 12.sp) }
                if (rules.isNotEmpty()) TextButton({ vm.agenticOs.learnings.writeText("# Learnings\n") }) { Text("Clear rules", color = Danger) }
                val outs = remember(vm.tasks.size) { vm.agenticOs.outputs.walkTopDown().count { it.isFile } }
                Text("Saved outputs: $outs · Slash commands: /code and /<skill name>", color = Dim, fontSize = 12.sp)
            }
        }

        item {
            Section("Automations") {
                Text("Zapier connects Syntra to YouTube, Instagram, Gmail, Sheets and 8,000+ apps. At mcp.zapier.com create a server, " +
                    "choose \"Other\", add the actions you want, then paste the connection token here.", color = Dim, fontSize = 12.sp)
                val zap = s.mcpServers.firstOrNull { it.name == "zapier" }
                var zapToken by remember { mutableStateOf(zap?.token.orEmpty()) }
                OutlinedTextField(zapToken, { zapToken = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Zapier MCP token") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row {
                    TextButton({
                        val others = s.mcpServers.filterNot { it.name == "zapier" }
                        vm.updateSettings(s.copy(mcpServers = if (zapToken.isBlank()) others else others + McpServer("zapier", "https://mcp.zapier.com/api/v1/connect", zapToken)))
                    }) { Text(if (zap == null) "Connect Zapier" else "Update Zapier") }
                    if (zap != null) TextButton({
                        mcpMsg = "Connecting…"
                        scope.launch { mcpMsg = runCatching { McpClient(zap).listTools().toString().take(600) }.getOrElse { "Failed: ${it.message}" } }
                    }) { Text("Test") }
                }
                Text("Telegram: talk to Syntra from anywhere. Create a bot with @BotFather, paste its token, send it a message, " +
                    "then add the chat id it replies with.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(s.telegramToken, { vm.updateSettings(s.copy(telegramToken = it.trim())) }, Modifier.fillMaxWidth(),
                    label = { Text("Telegram bot token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(s.telegramChats, { vm.updateSettings(s.copy(telegramChats = it)) }, Modifier.fillMaxWidth(),
                    label = { Text("Allowed chat ids (comma-separated)") }, singleLine = true)
                if (vm.telegramStatus.isNotBlank()) Text(vm.telegramStatus, color = Dim, fontSize = 12.sp)
            }
        }

        item {
            Section("Speed") {
                Text("Syntra keeps one model in RAM (8 GB phones) and gives each request only the tools it needs.", color = Dim, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU acceleration (experimental)")
                        Text("Runs the model on the Mali GPU via Vulkan. Can be faster at reading prompts; turn off if replies break.", color = Dim, fontSize = 12.sp)
                    }
                    Switch(s.useGpu, { vm.updateSettings(s.copy(useGpu = it)); vm.reloadModels() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("d1 router")
                        Text("When keywords can't tell what a request needs, ask the d1 decision model. Swaps models on 8 GB, so it adds a few seconds.", color = Dim, fontSize = 12.sp)
                    }
                    Switch(s.useD1Router, { vm.updateSettings(s.copy(useD1Router = it)) })
                }
            }
        }

        item {
            Section("Linux & local API") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask before Linux commands")
                        Text("Off lets the coding harness run commands in the app's Ubuntu sandbox without asking.", color = Dim, fontSize = 12.sp)
                    }
                    Switch(s.askLinux, { vm.updateSettings(s.copy(askLinux = it)) })
                }
                Text("Local AI API (for Hermes, Aider, scripts): http://127.0.0.1:8099/v1 · model \"syntra\" (on-device) or " +
                    "\"openrouter/<model>\" (cloud). Ubuntu already has OPENAI_BASE_URL and OPENAI_API_KEY set.", color = Dim, fontSize = 12.sp)
            }
        }

        item {
            Section("Memory") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic memory")
                        Text("After each reply, the lead model saves what matters to the graph.", color = Dim, fontSize = 12.sp)
                    }
                    Switch(s.autoMemory, { vm.updateSettings(s.copy(autoMemory = it)) })
                }
            }
        }

        item {
            Section("Model team (LFM3 family)") {
                Text("Lead model — plans and calls tools. The others are consulted via ask_specialist.", color = Dim, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelRole.entries.take(3).forEach { r -> FilterChip(s.primaryRole == r, { vm.updateSettings(s.copy(primaryRole = r)) }, { Text(r.name.lowercase()) }) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelRole.entries.drop(3).filter { it != ModelRole.DECISION }.forEach { r -> FilterChip(s.primaryRole == r, { vm.updateSettings(s.copy(primaryRole = r)) }, { Text(r.name.lowercase()) }) }
                }
                s.slots.forEach { slot ->
                    Text(slot.role.label, color = Violet, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(slot.model, { m -> vm.updateSettings(s.copy(slots = s.slots.map { if (it.role == slot.role) it.copy(model = m) else it })) },
                        Modifier.fillMaxWidth(), label = { Text("Model id") }, singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.providers.forEach { p ->
                            FilterChip(slot.providerId == p.id, {
                                vm.updateSettings(s.copy(slots = s.slots.map { if (it.role == slot.role) it.copy(providerId = p.id) else it }))
                            }, { Text(p.name.take(14)) })
                        }
                    }
                }
            }
        }

        item {
            Section("Providers") {
                s.providers.forEachIndexed { i, p ->
                    fun set(np: Provider) = vm.updateSettings(s.copy(providers = s.providers.toMutableList().also { it[i] = np }))
                    OutlinedTextField(p.name, { set(p.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(p.baseUrl, { set(p.copy(baseUrl = it.trim())) }, Modifier.fillMaxWidth(), label = { Text("Base URL (…/v1)") }, singleLine = true)
                    OutlinedTextField(p.apiKey, { set(p.copy(apiKey = it.trim())) }, Modifier.fillMaxWidth(), label = { Text("API key / token") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (!p.local) OutlinedTextField(p.agentModel, { set(p.copy(agentModel = it.trim())) }, Modifier.fillMaxWidth(),
                        label = { Text("Delegate agent id (e.g. openclaw) — blank if not an agent") }, singleLine = true)
                    TextButton({ vm.updateSettings(s.copy(providers = s.providers - p)) }) { Text("Remove ${p.name}", color = Danger) }
                }
                TextButton({ vm.updateSettings(s.copy(providers = s.providers + Provider("p${System.currentTimeMillis()}", "New provider", "https://"))) }) {
                    Icon(Icons.Outlined.Add, null); Text("Add provider")
                }
            }
        }

        item {
            Section("MCP servers (Streamable HTTP)") {
                s.mcpServers.forEachIndexed { i, m ->
                    fun set(nm: McpServer) = vm.updateSettings(s.copy(mcpServers = s.mcpServers.toMutableList().also { it[i] = nm }))
                    OutlinedTextField(m.name, { set(m.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(m.url, { set(m.copy(url = it.trim())) }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true)
                    OutlinedTextField(m.token, { set(m.copy(token = it.trim())) }, Modifier.fillMaxWidth(), label = { Text("Bearer token (optional)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row {
                        TextButton({
                            mcpMsg = "Connecting…"
                            scope.launch { mcpMsg = runCatching { McpClient(m).listTools().toString().take(600) }.getOrElse { "Failed: ${it.message}" } }
                        }) { Text("Test") }
                        TextButton({ vm.updateSettings(s.copy(mcpServers = s.mcpServers - m)) }) { Text("Remove", color = Danger) }
                    }
                }
                if (mcpMsg.isNotBlank()) Text(mcpMsg, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Dim)
                TextButton({ vm.updateSettings(s.copy(mcpServers = s.mcpServers + McpServer("server${s.mcpServers.size + 1}", "https://"))) }) {
                    Icon(Icons.Outlined.Add, null); Text("Add MCP server")
                }
            }
        }

        item {
            Section("GitHub") {
                OutlinedTextField(s.githubToken, { vm.updateSettings(s.copy(githubToken = it.trim())) }, Modifier.fillMaxWidth(),
                    label = { Text("Personal access token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Reads run freely; any write (POST/PATCH/PUT/DELETE) asks you first.", color = Dim, fontSize = 12.sp)
            }
        }

        item {
            Section("Voice") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Always listening for wake word", Modifier.weight(1f))
                    Switch(s.alwaysListening, { vm.updateSettings(s.copy(alwaysListening = it)) })
                }
                OutlinedTextField(s.wakeWords, { vm.updateSettings(s.copy(wakeWords = it)) }, Modifier.fillMaxWidth(),
                    label = { Text("Wake words (comma-separated)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speak replies aloud", Modifier.weight(1f))
                    Switch(s.speakReplies, { vm.updateSettings(s.copy(speakReplies = it)) })
                }
            }
        }
    }
}

/** Opens a produced file with whatever app handles it (via FileProvider). */
fun openFile(ctx: android.content.Context, path: String) {
    val f = java.io.File(path)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "text/plain"
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(android.content.Intent.createChooser(i, f.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FileChips(files: List<String>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        files.filter { java.io.File(it).exists() }.forEach { p ->
            androidx.compose.material3.AssistChip(onClick = { openFile(ctx, p) }, label = { Text(java.io.File(p).name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Description, null) })
        }
    }
}
