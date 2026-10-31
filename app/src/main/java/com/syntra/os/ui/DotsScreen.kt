package com.syntra.os.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syntra.os.AppViewModel
import com.syntra.os.agent.Dot
import com.syntra.os.core.ModelRole

private val GROUPS = listOf("WEB" to "Web research", "PHONE" to "Control phone", "CODE" to "Linux, code & tools", "AUTOMATION" to "Schedules & apps (MCP)",
    "GITHUB" to "GitHub", "TEAM" to "Ask other models")

/** Specialist agents: who they are, what they may touch, and which model leads them. */
@Composable
fun DotsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<Dot?>(null) }
    val scheme = MaterialTheme.colorScheme
    editing?.let { DotEditor(it, onSave = { d -> runCatching { vm.saveDot(d) }; editing = null }, onDelete = { vm.deleteDot(it.id); editing = null },
        onDismiss = { editing = null }, isNew = vm.dots.none { d -> d.id == it.id }) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Dots are specialists you call with @name in chat, e.g. \"@scout compare two phones\". Each has its own instructions, permissions and files.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            FilledTonalButton({ editing = Dot(System.currentTimeMillis().toString(36), "", "", setOf("WEB")) }, Modifier.padding(top = 10.dp)) { Text("New Dot") }
        }
        items(vm.dots, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth().clickable { editing = d }, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("@${d.slug} · ${d.name}", style = MaterialTheme.typography.titleSmall)
                    Text(d.instructions, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((GROUPS.filter { it.first in d.routes }.map { it.second } + listOfNotNull(d.role?.label)).joinToString(" · ").ifBlank { "Writing only" },
                        style = MaterialTheme.typography.labelMedium, color = scheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DotEditor(d0: Dot, isNew: Boolean, onSave: (Dot) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(d0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "New Dot" else d0.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.name, { d = d.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.instructions, { d = d.copy(instructions = it.take(3000)) }, label = { Text("Instructions") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("May use", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GROUPS.forEach { (k, label) -> FilterChip(k in d.routes, { d = d.copy(routes = if (k in d.routes) d.routes - k else d.routes + k) }, { Text(label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shared memory", Modifier.weight(1f)); Switch(d.memoryAllowed, { d = d.copy(memoryAllowed = it) })
                }
                Text("Lead model", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(d.role == null, { d = d.copy(role = null) }, { Text("Main") })
                    listOf(ModelRole.TEXT, ModelRole.VISION, ModelRole.OMNI).forEach { r -> FilterChip(d.role == r, { d = d.copy(role = r) }, { Text(r.label) }) }
                }
            }
        },
        confirmButton = { TextButton({ onSave(d) }, enabled = d.name.isNotBlank()) { Text("Save") } },
        dismissButton = { Row { if (!isNew) TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }; TextButton(onDismiss) { Text("Cancel") } } })
}
