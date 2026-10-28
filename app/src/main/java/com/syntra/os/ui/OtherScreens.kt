package com.syntra.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
fun TasksScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("TASKS", "Every agent run, its tool calls, and which models took part.") }
        if (vm.tasks.isEmpty()) item { Text("No tasks yet.", color = Dim) }
        items(vm.tasks, key = { it.id }) { t ->
            val color = when (t.status) {
                TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL -> Cyan; TaskStatus.DONE -> Ok
                TaskStatus.FAILED -> Danger; TaskStatus.CANCELLED -> Dim
            }
            Card(Modifier.fillMaxWidth().clickable { open = if (open == t.id) null else t.id }, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(color, CircleShape)); Spacer(Modifier.size(8.dp))
                        Text(t.goal, maxLines = 2, modifier = Modifier.weight(1f))
                        Text(t.status.name.lowercase(), color = color, fontSize = 11.sp)
                    }
                    val models = t.steps.map { it.who }.filter { it.contains("LFM", true) || it.contains("Omni", true) || '/' in it || '-' in it }.distinct()
                    Text("${t.steps.size} steps" + if (models.isNotEmpty()) " · ${models.joinToString()}" else "", color = Dim, fontSize = 11.sp)
                    if (open == t.id) t.steps.forEach { s ->
                        Text("${s.kind.name.lowercase()} · ${s.who}", color = if (s.kind == StepKind.ERROR) Danger else Violet, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 8.dp))
                        Text(s.text.take(800), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
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
        item { Header("SETUP", "Pick models by hand. Every provider uses the OpenAI-compatible API.") }

        item {
            Section("Model team (LFM3 family)") {
                Text("Lead model — plans and calls tools. The others are consulted via ask_specialist.", color = Dim, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelRole.entries.take(3).forEach { r -> FilterChip(s.primaryRole == r, { vm.updateSettings(s.copy(primaryRole = r)) }, { Text(r.name.lowercase()) }) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelRole.entries.drop(3).forEach { r -> FilterChip(s.primaryRole == r, { vm.updateSettings(s.copy(primaryRole = r)) }, { Text(r.name.lowercase()) }) }
                }
                s.slots.forEach { slot ->
                    Text(slot.role.label, color = Violet, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(slot.model, { m -> vm.updateSettings(s.copy(slots = s.slots.map { if (it.role == slot.role) it.copy(model = m) else it })) },
                        Modifier.fillMaxWidth(), label = { Text("Model id") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Text("Speak replies aloud", Modifier.weight(1f))
                    Switch(s.speakReplies, { vm.updateSettings(s.copy(speakReplies = it)) })
                }
            }
        }
    }
}
