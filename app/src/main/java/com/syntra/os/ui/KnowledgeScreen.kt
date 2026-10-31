package com.syntra.os.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syntra.os.AppViewModel

/** Your documents: import, semantic-search setup, and a search box to check what the agent will retrieve. */
@Composable
fun KnowledgeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var q by rememberSaveable { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.importDocuments(it) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Add PDFs, Word files, notes or code. Syntra answers from them and cites the page.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                Button({ pick.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*", "application/json")) },
                    enabled = !vm.knowledgeBusy) { Text("Add documents") }
                if (!vm.embedderInstalled) FilledTonalButton(vm::downloadEmbedder, enabled = !vm.knowledgeBusy) { Text("Enable semantic search") }
            }
            Text(if (vm.embedderInstalled) "Hybrid search: keywords + meaning (EmbeddingGemma, on device)."
                else "Keyword search now. Semantic search needs a 330 MB on-device model that understands Hindi and Marathi too.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (vm.knowledgeBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (vm.knowledgeStatus.isNotBlank()) Text(vm.knowledgeStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        if (vm.knowledgeDocs.isNotEmpty()) item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Test a search") },
                trailingIcon = { IconButton({ vm.searchKnowledge(q) }, enabled = q.isNotBlank() && !vm.knowledgeBusy) { Icon(Icons.Outlined.Search, "Search") } })
        }
        items(vm.knowledgeHits, key = { "hit" + it.chunk.id }) { h ->
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp)) {
                    Text(h.cite, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    Text(h.chunk.text, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(h.bm25Rank?.let { "keyword #${it + 1}" }, h.denseRank?.let { "meaning #${it + 1}" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (vm.knowledgeDocs.isNotEmpty()) item { Text("Documents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        items(vm.knowledgeDocs, key = { it.id }) { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.chunks} passages" + (if (d.pages > 0) " · ${d.pages} pages" else "") + if (d.embedModel.isBlank()) " · keywords only" else "",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                IconButton({ vm.removeDocument(d.id) }, enabled = !vm.knowledgeBusy) { Icon(Icons.Outlined.Delete, "Remove ${d.title}") }
            }
        }
    }
}
