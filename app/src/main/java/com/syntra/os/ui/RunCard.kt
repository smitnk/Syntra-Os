package com.syntra.os.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syntra.os.core.Step
import com.syntra.os.core.StepKind

/** One visible action in a run: a tool call paired with its result, or a thought. */
data class RunItem(val kind: StepKind, val tool: String, val detail: String, val result: String?, val failed: Boolean)

/** Pairs each TOOL_CALL with the next TOOL_RESULT of the same tool; keeps thoughts; drops the final answer. */
fun runItems(steps: List<Step>): List<RunItem> {
    val out = mutableListOf<RunItem>()
    val open = mutableListOf<Int>()
    steps.forEach { s ->
        when (s.kind) {
            StepKind.TOOL_CALL -> { open += out.size; out += RunItem(s.kind, s.who, s.text, null, false) }
            StepKind.TOOL_RESULT -> {
                val i = open.firstOrNull { out[it].tool == s.who }
                val failed = s.text.startsWith("Error:") || "[verification failed" in s.text || s.text == "User denied this action."
                if (i != null) { open.remove(i); out[i] = out[i].copy(result = s.text, failed = failed) }
            }
            StepKind.THOUGHT -> out += RunItem(s.kind, s.who, s.text, s.text, false)
            StepKind.ERROR -> out += RunItem(s.kind, "error", s.text, s.text, true)
            else -> {}
        }
    }
    return out
}

private fun toolLabel(t: String): Pair<String, ImageVector> = when {
    t == "web_search" -> "Searching the web" to Icons.Outlined.Search
    t == "web_fetch" || t == "scrape" -> "Reading a page" to Icons.Outlined.Language
    t.startsWith("phone_") || t == "open_app" || t == "list_apps" || t == "read_notifications" -> "Using your phone" to Icons.Outlined.Smartphone
    t in setOf("shell", "code_agent", "claude_code", "hermes_agent") -> "Running in Linux" to Icons.Outlined.Terminal
    t.startsWith("file_") || t == "save_output" -> "Working with files" to Icons.Outlined.Description
    t.startsWith("memory") || t == "learn" -> "Memory" to Icons.Outlined.Psychology
    t == "todo" -> "Planning" to Icons.Outlined.Lightbulb
    t.contains("schedule") -> "Scheduling" to Icons.Outlined.Schedule
    t.startsWith("mcp") || t == "http_request" || t == "github" -> "Connected app" to Icons.Outlined.Apps
    else -> t.replace('_', ' ') to Icons.Outlined.Bolt
}

/** Short, human detail from the tool's JSON args (query, url, command, path…). */
private fun shortDetail(json: String): String =
    Regex("\"(query|url|command|path|name|target|task|goal|text)\"\\s*:\\s*\"([^\"]{1,140})").find(json)?.groupValues?.get(2) ?: ""

@Composable
private fun Pulse(modifier: Modifier = Modifier) {
    val reduce = LocalReducedMotion.current
    val a by rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(modifier.size(8.dp).alpha(if (reduce) 1f else a).background(MaterialTheme.colorScheme.primary, CircleShape))
}

/** Timeline of a run's actions; used live in chat, under finished answers, and in Tasks. */
@Composable
fun StepTimeline(steps: List<Step>, live: Boolean, modifier: Modifier = Modifier) {
    val items = runItems(steps)
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, it ->
            var open by remember { mutableStateOf(false) }
            val running = live && it.result == null && i == items.lastIndex
            val (label, icon) = if (it.kind == StepKind.THOUGHT) "Thinking" to Icons.Outlined.Lightbulb else toolLabel(it.tool)
            val state = when { running -> "in progress"; it.failed -> "failed"; else -> "done" }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp).animateContentSize()
                .semantics(mergeDescendants = true) { stateDescription = state }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp), tint = if (it.failed) scheme.error else scheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                        val d = if (it.kind == StepKind.TOOL_CALL) shortDetail(it.detail) else it.detail.lines().first()
                        if (d.isNotBlank()) Text(d, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        it.failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = scheme.error)
                        it.kind == StepKind.TOOL_CALL -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = scheme.primary)
                    }
                }
                if (open && it.result != null && it.kind == StepKind.TOOL_CALL) Surface(color = scheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(start = 28.dp, top = 6.dp).fillMaxWidth()) {
                    Text(it.result.take(800), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}

/**
 * The live card shown while Syntra works: progress, the action timeline, the reply as it streams,
 * and the approval prompt inline — so the user sees what is happening without leaving the chat.
 */
@Composable
fun RunCard(steps: List<Step>, streaming: String, approval: String?, onAllow: () -> Unit, onDeny: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val n = runItems(steps).count { it.kind == StepKind.TOOL_CALL }
    Surface(color = scheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()
        .semantics { contentDescription = "Syntra is working, $n actions so far" }) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pulse(); Spacer(Modifier.width(10.dp))
                Text(if (approval != null) "Waiting for you" else if (n == 0) "Thinking" else "Working · $n ${if (n == 1) "action" else "actions"}",
                    style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = scheme.primary, trackColor = scheme.surfaceContainerHighest)
            if (steps.isNotEmpty()) { Spacer(Modifier.height(8.dp)); StepTimeline(steps, live = approval == null) }
            AnimatedVisibility(approval != null, enter = fadeIn() + expandVertically()) {
                Surface(color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        val risk = approval.orEmpty().substringAfter('[', "").substringBefore(']')
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Shield, null, tint = scheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("Allow this action?", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                        }
                        if (risk.isNotBlank()) Text(risk, style = MaterialTheme.typography.labelMedium, color = scheme.primary, modifier = Modifier.padding(top = 4.dp))
                        Text(approval.orEmpty().substringAfter("] ").take(300), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onAllow) { Text("Allow") }
                            OutlinedButton(onDeny) { Text("Deny") }
                        }
                    }
                }
            }
            val shown = streaming.replace(Regex("<tool(_call)?>.*?(</tool(_call)?>|$)", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL), "").trim()
            if (shown.isNotBlank()) Text(shown, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Collapsed "n steps" line under a finished answer that expands to the full timeline. */
@Composable
fun StepsDisclosure(steps: List<Step>) {
    val n = runItems(steps).count { it.kind == StepKind.TOOL_CALL }
    if (n == 0) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Row(Modifier.clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Code, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("$n ${if (n == 1) "action" else "actions"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Hide steps" else "Show steps",
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) StepTimeline(steps, live = false, modifier = Modifier.padding(start = 4.dp))
    }
}
