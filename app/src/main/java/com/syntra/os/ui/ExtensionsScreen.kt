package com.syntra.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syntra.os.AppViewModel
import com.syntra.os.ext.InstallPlan
import com.syntra.os.ext.Registry

/** Install skills, agents and tools from GitHub — always through a reviewed install plan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var url by rememberSaveable { mutableStateOf("") }
    var skillFilter by rememberSaveable { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    vm.plan?.let { PlanSheet(it, onApprove = vm::approvePlan, onCancel = vm::cancelPlan) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Extensions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Skills, agents and tools from GitHub. You see every file, permission and command before anything installs.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("GitHub repo") }, placeholder = { Text("owner/repo or github.com link") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Registry.featured.forEach { (r, _) -> AssistChip(onClick = { url = r }, label = { Text(r.substringAfter('/')) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ vm.inspectExtension(url) }, enabled = url.isNotBlank() && !vm.extBusy) { Text("Inspect") }
                        TextButton(vm::checkUpdates, enabled = vm.extensions.isNotEmpty() && !vm.extBusy) { Text("Check for updates") }
                    }
                    if (vm.extBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.extStatus.isNotBlank()) Text(vm.extStatus, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (vm.extensions.isNotEmpty()) item { Text("Installed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        items(vm.extensions, key = { it.manifest.id }) { e ->
            var open by remember { mutableStateOf(false) }
            val m = e.manifest
            Card(Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize(),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (m.type) { "agent" -> Icons.Outlined.Groups; "tool" -> Icons.Outlined.Build; "skill" -> Icons.Outlined.MenuBook
                            else -> Icons.Outlined.Extension }, null, tint = scheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text("${e.repo} @ ${e.sha.take(7)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        Switch(e.enabled, { vm.toggleExtension(m.id) })
                    }
                    Text(listOfNotNull(m.skills.size.takeIf { it > 0 }?.let { "$it skills" }, m.agents.size.takeIf { it > 0 }?.let { "$it agents" },
                        m.tools.size.takeIf { it > 0 }?.let { "$it tools" }).joinToString(" · ").ifBlank { m.type },
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    if (m.tools.isNotEmpty() && !e.toolsReady) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp), tint = scheme.tertiary); Spacer(Modifier.width(6.dp))
                        Text("Dependencies not installed", style = MaterialTheme.typography.bodySmall, color = scheme.tertiary, modifier = Modifier.weight(1f))
                        TextButton({ vm.setupExtension(m.id) }, enabled = !vm.extBusy) { Text("Set up") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.extUpdates.containsKey(m.id)) FilledTonalButton({ vm.updateExtension(m.id) }) { Text("Update") }
                        TextButton({ vm.removeExtension(m.id) }) { Text("Remove", color = scheme.error) }
                    }
                    if (open) {
                        m.tools.forEach { Text("Tool · ${it.name}: ${it.description}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                        if (e.log.isNotBlank()) Surface(color = scheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 8.dp)) {
                            Text(e.log.takeLast(600), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }
        }
        item {
            Text("Skills & agents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }.padding(top = 8.dp))
            Text("Use any of them with /name in chat. Switched-on skills are added to every task (keep few on for speed).",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (vm.skills.size > 12) OutlinedTextField(skillFilter, { skillFilter = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true, label = { Text("Filter ${vm.skills.size} skills") })
        }
        val shown = vm.skills.filter { skillFilter.isBlank() || skillFilter.lowercase() in (it.name + " " + it.description).lowercase() }.take(150)
        items(shown, key = { it.name + it.source }) { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text("/" + com.syntra.os.agent.AgenticOs.slug(s.name) + " · " + s.description, style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Switch(s.enabled, { vm.toggleSkill(s) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(p: InstallPlan, onApprove: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val m = p.manifest
    ModalBottomSheet(onDismissRequest = onCancel, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Install ${m.name}?", style = MaterialTheme.typography.headlineSmall)
            Text("${p.repo} @ ${p.sha.take(7)} · ${m.type}" + if (p.generated) " · manifest generated by Syntra" else " · repo manifest",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Section("Adds") {
                Text("${m.skills.size} skills · ${m.agents.size} agents · ${m.tools.size} tools", style = MaterialTheme.typography.bodyMedium)
                (m.skills.map { it.name } + m.agents.map { it.name }).take(12).let { if (it.isNotEmpty()) Text(it.joinToString(", ") +
                    if (m.skills.size + m.agents.size > 12) "…" else "", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                m.tools.forEach { Text("${it.name} — ${it.description}", style = MaterialTheme.typography.bodySmall) }
            }
            Section("Downloads") {
                Text("${p.files.size} files, ${p.bytes / 1024} KB, pinned to this commit", style = MaterialTheme.typography.bodyMedium)
                p.files.take(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = scheme.onSurfaceVariant) }
            }
            if (m.permissions.isNotEmpty()) Section("Permissions") {
                m.permissions.forEach { Text("• " + when (it) { "network" -> "Internet access from Linux"; "linux.exec" -> "Runs code in Syntra's Linux sandbox"; else -> it },
                    style = MaterialTheme.typography.bodyMedium) }
            }
            Section("Commands Syntra will run") {
                if (p.commands.isEmpty()) Text("None", style = MaterialTheme.typography.bodyMedium)
                p.commands.forEach { c ->
                    Surface(color = scheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Text(c, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.padding(10.dp))
                    }
                }
                m.tools.filter { it.inline.isNotBlank() }.forEach { t ->
                    Text("Tool script ${t.name}:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    Surface(color = scheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Text(t.inline, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), modifier = Modifier.padding(10.dp))
                    }
                }
            }
            if (p.ignoredScripts.isNotEmpty()) Section("Not run") {
                Text("The repo has scripts Syntra will never execute: " + p.ignoredScripts.take(8).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            p.warnings.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp), tint = scheme.tertiary); Spacer(Modifier.width(6.dp))
                    Text(w, style = MaterialTheme.typography.bodySmall, color = scheme.tertiary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onApprove, enabled = m.skills.size + m.agents.size + m.tools.size + m.dependencies.pip.size > 0) { Text("Approve & install") }
                OutlinedButton(onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
    content()
}
