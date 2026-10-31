package com.syntra.os.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.syntra.os.core.StepKind
import com.syntra.os.core.TaskRun
import com.syntra.os.core.TaskStatus

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}

@Composable
private fun StatusIcon(s: TaskStatus) {
    val c = MaterialTheme.colorScheme
    when (s) {
        TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        TaskStatus.DONE -> Icon(Icons.Outlined.CheckCircle, "Done", tint = c.primary)
        TaskStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, "Failed", tint = c.error)
        TaskStatus.INTERRUPTED -> Icon(Icons.Outlined.PauseCircle, "Interrupted", tint = c.tertiary)
        TaskStatus.CANCELLED -> Icon(Icons.Outlined.RadioButtonUnchecked, "Cancelled", tint = c.onSurfaceVariant)
    }
}

/** Tasks: everything Syntra did or is doing, filterable, resumable, with the full action timeline and produced files. */
@Composable
fun TasksScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var open by remember { mutableStateOf<String?>(null) }
    val resumable = remember(vm.tasks, vm.busy) { vm.resumable() }
    val scheme = MaterialTheme.colorScheme
    val shown = vm.tasks.filter {
        when (filter) {
            "active" -> it.status == TaskStatus.RUNNING || it.status == TaskStatus.WAITING_APPROVAL || it.status == TaskStatus.INTERRUPTED
            "done" -> it.status == TaskStatus.DONE
            else -> true
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Tasks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            val active = vm.tasks.count { it.status == TaskStatus.RUNNING }
            val paused = vm.tasks.count { it.status == TaskStatus.INTERRUPTED }
            Text("${vm.tasks.size} total · $active running · $paused paused", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "active" to "Active", "done" to "Done").forEach { (k, l) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l) })
                }
            }
        }
        if (vm.schedules.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, null, tint = scheme.primary); Spacer(Modifier.width(8.dp))
                        Text("Scheduled", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    }
                    vm.schedules.forEach { (id, goal) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(goal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton({ vm.cancelSchedule(id) }) { Text("Cancel", color = scheme.error) }
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) item {
            Text(if (vm.tasks.isEmpty()) "Nothing yet. Ask Syntra to do something in Agent or Code mode." else "No tasks here.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
        items(shown, key = { it.id }) { t -> TaskCard(vm, t, open == t.id, t.id in resumable) { open = if (open == t.id) null else t.id } }
    }
}

@Composable
private fun TaskCard(vm: AppViewModel, t: TaskRun, expanded: Boolean, canResume: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val actions = runItems(t.steps).count { it.kind == StepKind.TOOL_CALL }
    val answer = t.steps.lastOrNull { it.kind == StepKind.ANSWER }
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle).animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = if (expanded) scheme.surfaceContainerHigh else scheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StatusIcon(t.status); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.goal, style = MaterialTheme.typography.titleSmall, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                    Text("${ago(t.startedAt)} · $actions ${if (actions == 1) "action" else "actions"}" +
                        if (answer?.files?.isNotEmpty() == true) " · ${answer.files.size} files" else "",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (t.status == TaskStatus.INTERRUPTED && canResume) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ vm.resumeTask(t.id) }, enabled = !vm.busy) { Text("Resume") }
                TextButton({ vm.discardCheckpoint(t.id) }) { Text("Discard") }
            }
            answer?.files?.takeIf { it.isNotEmpty() }?.let { Spacer(Modifier.size(8.dp)); FileChips(it) }
            if (expanded) {
                Spacer(Modifier.size(10.dp))
                StepTimeline(t.steps, live = t.status == TaskStatus.RUNNING)
                answer?.let {
                    Surface(color = scheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
                        Text(it.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    }
                }
                if (answer?.verified == false) Text("Not fully verified: " + answer.notes.joinToString("; "),
                    style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
