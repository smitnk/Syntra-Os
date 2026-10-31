package com.syntra.os.ui

import android.provider.Settings as SysSettings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntra.os.AppViewModel
import com.syntra.os.core.ModelRole
import com.syntra.os.core.StepKind
import com.syntra.os.voice.VoiceState

@Composable
private fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { SysSettings.Global.getFloat(ctx.contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The presence orb: glowing core, rotating sweep ring, counter-rotating dashed ring, orbiting sparks,
 * and a circular waveform that reacts to your voice ([level]) and to how hard Syntra is working ([energy]).
 */
@Composable
fun Orb(energy: Float, modifier: Modifier = Modifier, level: Float = 0f) {
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "phase")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breath")
    val p = if (still) 0f else phase
    val br = if (still) 0.5f else breath
    val e = (energy + level * 0.8f).coerceIn(0f, 1.4f)
    Canvas(modifier.semantics { contentDescription = "Syntra presence" }) {
        val r = size.minDimension / 2
        val c = center
        val turn = p * 360f * (1f + e)
        // halo
        drawCircle(Brush.radialGradient(listOf(Clay.copy(alpha = 0.28f + 0.25f * e), Clay.copy(alpha = 0.05f), Color.Transparent),
            c, r * (0.95f + 0.05f * br)), r)
        // waveform ring
        val bars = 72
        val base = r * 0.62f
        for (i in 0 until bars) {
            val ang = Math.toRadians((i * 360.0 / bars) + turn * 0.25)
            val wob = (kotlin.math.sin(i * 0.9 + p * 40) * 0.5 + kotlin.math.sin(i * 0.37 - p * 23) * 0.5).toFloat()
            val len = r * (0.025f + (0.05f + 0.16f * e) * (0.5f + 0.5f * wob))
            val cx = kotlin.math.cos(ang).toFloat(); val cy = kotlin.math.sin(ang).toFloat()
            drawLine(ClaySoft.copy(alpha = 0.35f + 0.5f * e.coerceAtMost(1f)),
                Offset(c.x + cx * base, c.y + cy * base), Offset(c.x + cx * (base + len), c.y + cy * (base + len)),
                strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        }
        // sweep ring
        val rr = r * 0.9f
        rotate(turn, c) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Clay.copy(alpha = 0.2f), Clay, ClaySoft, Color.Transparent), c),
                rr, c, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
        }
        // dashed counter ring
        rotate(-turn * 0.6f, c) {
            drawCircle(Clay.copy(alpha = 0.45f), r * 0.78f, c,
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 9.dp.toPx()))))
        }
        // orbiting sparks
        repeat(3) { k ->
            val ang = Math.toRadians((turn * (1.2 + k * 0.35) + k * 120).toDouble())
            val orbit = r * (0.84f - k * 0.05f)
            val sp = Offset(c.x + kotlin.math.cos(ang).toFloat() * orbit, c.y + kotlin.math.sin(ang).toFloat() * orbit)
            drawCircle(Brush.radialGradient(listOf(ClaySoft, Clay.copy(alpha = 0f)), sp, 7.dp.toPx()), 7.dp.toPx(), sp)
            drawCircle(Color.White.copy(alpha = 0.9f), 1.6.dp.toPx(), sp)
        }
        // core sphere with highlight
        val core = r * (0.36f + 0.05f * br + 0.08f * level)
        drawCircle(Brush.radialGradient(listOf(Clay.copy(alpha = 0.55f), Color.Transparent), c, core * 1.6f), core * 1.6f, c)
        drawCircle(Brush.radialGradient(listOf(ClaySoft, Clay, ClayDeep, Color(0xFF5A2A1A)),
            Offset(c.x - core * 0.3f, c.y - core * 0.35f), core * 1.25f), core, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
            Offset(c.x - core * 0.35f, c.y - core * 0.4f), core * 0.45f), core * 0.45f, Offset(c.x - core * 0.35f, c.y - core * 0.4f))
    }
}

@Composable
private fun TeamRow(active: Set<ModelRole>, busy: Boolean, installed: Set<ModelRole> = ModelRole.entries.toSet()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ModelRole.shown.forEach { r ->
            val on = r in active
            Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(30.dp).background(if (on) Clay else Raised, CircleShape)
                    .border(if (r in installed) 1.5.dp else 1.dp, if (on) ClaySoft else if (r in installed) Clay.copy(alpha = 0.6f) else Line, CircleShape)
                    .semantics { contentDescription = "${r.label} model ${if (on) "working" else if (r in installed) "ready" else "not downloaded"}" },
                    contentAlignment = Alignment.Center) {
                    Text(r.name.take(1), color = if (on) Bg else Dim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text(r.name.lowercase(), color = if (on) Clay else Dim.copy(alpha = if (busy) 0.6f else 1f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ModeSwitch(voiceOn: Boolean, state: VoiceState, onChange: (Boolean) -> Unit) {
    Row(Modifier.background(Raised, RoundedCornerShape(50)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to "Chat", true to "Voice").forEach { (v, label) ->
            val sel = voiceOn == v
            Row(Modifier.background(if (sel) Clay else Color.Transparent, RoundedCornerShape(50)).clickable { onChange(v) }
                .padding(horizontal = 14.dp, vertical = 7.dp).semantics { contentDescription = "$label mode" },
                verticalAlignment = Alignment.CenterVertically) {
                if (v && sel) {
                    Box(Modifier.size(7.dp).background(if (state == VoiceState.LISTENING) Bg else Bg.copy(alpha = 0.5f), CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (sel) Bg else Dim)
            }
        }
    }
}

@Composable
fun AssistantScreen(vm: AppViewModel, onMic: () -> Unit, onToggleVoice: (Boolean) -> Unit, onAttach: () -> Unit, onMenu: () -> Unit = {}, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) HistorySheet(vm) { showHistory = false }
    val list = rememberLazyListState()
    val count = vm.chat.size + if (vm.busy) 1 else 0
    LaunchedEffect(count, vm.streaming.length / 80) { if (count > 0) list.animateScrollToItem(count - 1) }
    val energy = when { vm.busy -> 0.8f; vm.voiceState == VoiceState.LISTENING || vm.voiceState == VoiceState.SPEAKING -> 1f; else -> 0.15f }
    val empty = vm.chat.isEmpty() && !vm.busy

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onMenu) { Icon(Icons.Outlined.Menu, "Open menu: models and settings", tint = Ink) }
            IconButton({ showHistory = true }) { Icon(Icons.Outlined.History, "Chat history", tint = Ink) }
            Text("Syntra", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.weight(1f))
            ModeSwitch(vm.settings.alwaysListening, vm.voiceState, onToggleVoice)
            IconButton(vm::newChat) { Icon(Icons.Outlined.AddComment, "New chat", tint = Ink) }
        }

        if (empty) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Orb(energy, Modifier.size(240.dp), vm.micLevel)
                Spacer(Modifier.height(20.dp))
                Text(vm.heard.ifBlank { "Good to see you." }, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(when {
                    !vm.settings.alwaysListening -> "Chat mode — type below. Switch to Voice to talk."
                    vm.voiceState == VoiceState.LISTENING -> "Listening…"
                    else -> "Say \"${vm.settings.wakeWords.split(',').first().trim().replaceFirstChar { it.uppercase() }}\" or type below."
                }, color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(28.dp))
                TeamRow(vm.activeRoles, false, remember(vm.installedTick) { vm.engines.installedRoles().toSet() })
                Spacer(Modifier.height(24.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Research today's AI news", "/code build a to-do web app in Python", "What's on my screen?", "Every morning at 8, send me AI news").forEach { q ->
                        Text(q, fontSize = 13.sp, color = Ink, modifier = Modifier.border(1.dp, Line, RoundedCornerShape(50))
                            .clickable { vm.send(q) }.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Orb(energy, Modifier.size(52.dp), vm.micLevel); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { TeamRow(vm.activeRoles, vm.busy, remember(vm.installedTick) { vm.engines.installedRoles().toSet() }) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(vm.chat) { s ->
                    when (s.kind) {
                        StepKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), modifier = Modifier.fillMaxWidth(0.85f).wrapContentWidth(Alignment.End)) {
                                Text(s.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        StepKind.ERROR -> Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(s.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        else -> Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(s.who, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                if (s.verified == true && (s.files.isNotEmpty())) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Outlined.Verified, "Verified", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(s.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            if (s.files.isNotEmpty()) { Spacer(Modifier.height(8.dp)); FileChips(s.files) }
                            if (s.verified == false) Text("Not fully verified: " + s.notes.joinToString("; ").take(200),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                            s.taskId?.let { id -> vm.tasks.firstOrNull { it.id == id }?.let { StepsDisclosure(it.steps) } }
                        }
                    }
                }
                if (vm.busy) item {
                    RunCard(vm.tasks.firstOrNull { it.id == vm.currentTaskId }?.steps.orEmpty(), vm.streaming, vm.pendingApproval,
                        onAllow = { vm.answerApproval(true) }, onDeny = { vm.answerApproval(false) })
                }
            }
        }

        if (vm.heard.isNotBlank() && !empty) Text("“${vm.heard}”", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))

        if (input.startsWith("/") && !input.contains(' ')) {
            val opts = listOf("code" to "coding harness") + vm.skills.map { com.syntra.os.agent.AgenticOs.slug(it.name) to it.description }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.filter { it.first.startsWith(input.drop(1).lowercase()) }.take(8).forEach { (cmd, _) ->
                    Text("/$cmd", fontSize = 13.sp, color = Ink, modifier = Modifier.border(1.dp, Clay, RoundedCornerShape(50))
                        .clickable { input = "/$cmd " }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        vm.attachment?.let {
            Row(Modifier.padding(horizontal = 20.dp).background(Raised, RoundedCornerShape(50)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Image attached", fontSize = 12.sp, color = Ink)
                IconButton({ vm.attachment = null }, Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "Remove image", tint = Dim) }
            }
        }

        // Ask = answer only; Agent = tools as needed; Code = coding harness in Linux.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ask" to "Ask", "agent" to "Agent", "code" to "Code").forEach { (k, l) ->
                androidx.compose.material3.FilterChip(selected = vm.mode == k, onClick = { vm.mode = k }, label = { Text(l) },
                    leadingIcon = if (vm.mode == k) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge)
            .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onAttach) { Icon(Icons.Outlined.AddPhotoAlternate, "Attach image", tint = Dim) }
            TextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(when (vm.mode) { "ask" -> "Ask anything"; "code" -> "Describe what to build"; else -> "Tell Syntra what to do" }, color = Dim) }, maxLines = 4,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            val btn = IconButtonDefaults.filledIconButtonColors(containerColor = Clay, contentColor = Bg)
            when {
                vm.busy -> FilledIconButton(vm::cancel, Modifier.size(48.dp), colors = btn) { Icon(Icons.Outlined.Stop, "Stop") }
                input.isBlank() -> FilledIconButton(onMic, Modifier.size(48.dp), colors = btn) { Icon(Icons.Outlined.Mic, "Talk now") }
                else -> FilledIconButton({ vm.send(input); input = "" }, Modifier.size(48.dp), colors = btn) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(vm: AppViewModel, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.weight(1f))
                Button({ vm.newChat(); onClose() }, colors = ButtonDefaults.buttonColors(containerColor = Clay)) { Text("New chat", color = Bg) }
            }
            Spacer(Modifier.height(12.dp))
            if (vm.sessions.isEmpty()) Text("No saved chats yet.", color = Dim)
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(vm.sessions, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openChat(c.id); onClose() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = Ink, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                .format(java.util.Date(c.updated)) + " · ${c.steps.size} messages", color = Dim, fontSize = 12.sp)
                        }
                        IconButton({ vm.deleteChat(c.id) }) { Icon(Icons.Outlined.Delete, "Delete chat", tint = Dim) }
                    }
                }
            }
        }
    }
}
