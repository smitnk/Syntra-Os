package com.syntra.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syntra.os.AppViewModel

/** Any GGUF model from Hugging Face: install, probe capabilities, benchmark, make it the main model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var repo by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val main = vm.settings.mainModelId
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Main model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(main ?: "Built-in default (${vm.settings.slot(vm.settings.primaryRole).model})",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (main != null) TextButton({ vm.useAsMain(null) }) { Text("Back to built-in default") }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Install any model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("Paste a Hugging Face GGUF repo. Syntra picks the 4-bit file (Q4_0/Q4_K_M) and any vision projector. " +
                        "On 8 GB phones, models up to ~4B parameters fit.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("owner/repo") }, placeholder = { Text("unsloth/Qwen3-1.7B-GGUF") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.syntra.os.local.RECOMMENDED.forEach { (r, why) ->
                            AssistChip(onClick = { repo = r }, label = { Text(r.substringAfter('/').removeSuffix("-GGUF") + " · " + why) })
                        }
                    }
                    Button({ vm.installCustom(repo) }, enabled = repo.isNotBlank() && vm.modelBusy == null) { Text("Install") }
                    vm.modelInfo[repo.trim()]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    if (vm.modelBusy == repo) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        item { Text("Installed models", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        if (vm.customModels.isEmpty()) item {
            Text("None yet. Tap a recommended model above: one main model (LFM2.5-VL-1.6B) and the LFM2-350M router are all you need.", color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
        items(vm.customModels, key = { it }) { id ->
            val isMain = id == main
            Card(colors = CardDefaults.cardColors(containerColor = if (isMain) scheme.secondaryContainer else scheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (isMain) Text("MAIN", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    }
                    vm.modelInfo[id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                    if (vm.modelBusy == id) LinearProgressIndicator(Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isMain) FilledTonalButton({ vm.useAsMain(id) }) { Text("Use as main") }
                        if (vm.settings.routerModelId != id) TextButton({ vm.useAsRouter(id) }) { Text("Use as router") }
                        else Text("ROUTER", style = MaterialTheme.typography.labelSmall, color = scheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton({ vm.probeModel(id, bench = false) }, enabled = vm.modelBusy == null) { Text("Probe") }
                        TextButton({ vm.probeModel(id, bench = true) }, enabled = vm.modelBusy == null) { Text("Benchmark") }
                        TextButton({ vm.deleteCustom(id) }) { Text("Delete", color = scheme.error) }
                    }
                }
            }
        }
    }
}
