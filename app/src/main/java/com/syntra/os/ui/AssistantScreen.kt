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
import androidx.compose.material.icons.outlined.DeleteSweep
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

/** The presence orb. Breathes when idle, spins up while listening, thinking or speaking. */
@Composable
fun Orb(energy: Float, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween((12000 / (1 + energy * 4)).toInt(), easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0.9f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breath")
    val s = if (still) 0f else spin
    val b = if (still) 1f else breath
    Canvas(modifier.semantics { contentDescription = "Syntra presence" }) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Clay.copy(alpha = 0.35f + energy * 0.25f), Clay.copy(alpha = 0.06f), Color.Transparent), center, r * b), r * b)
        listOf(0.92f, 0.74f, 0.56f).forEachIndexed { i, k ->
            val rr = r * k
            drawArc(
                color = (if (i == 1) ClaySoft else Clay).copy(alpha = 0.9f - i * 0.22f),
                startAngle = s * (if (i % 2 == 0) 1f else -1.4f) + i * 60f, sweepAngle = 70f + 90f * (1 - i * 0.3f) + energy * 60f,
                useCenter = false, topLeft = center.copy(center.x - rr, center.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke((2.5f - i * 0.5f).dp.toPx()),
            )
        }
        drawCircle(Brush.radialGradient(listOf(ClaySoft, Clay, ClayDeep), center, r * 0.24f), r * 0.2f * b)
    }
}

@Composable
private fun TeamRow(active: Set<ModelRole>, busy: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ModelRole.entries.forEach { r ->
            val on = r in active
            Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(30.dp).background(if (on) Clay else Raised, CircleShape)
                    .border(1.dp, if (on) ClaySoft else Line, CircleShape)
                    .semantics { contentDescription = "${r.label} model ${if (on) "working" else "idle"}" },
                    contentAlignment = Alignment.Center) {
                    Text(r.name.take(1), color = if (on) Bg else Dim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text(r.name.lowercase(), color = if (on) Clay else Dim.copy(alpha = if (busy) 0.6f else 1f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun VoicePill(state: VoiceState, words: String, onClick: () -> Unit) {
    val (dot, text) = when (state) {
        VoiceState.OFF -> Dim to "Voice off"
        VoiceState.WAITING_WAKE -> Ok to "Say \"${words.split(',').first().trim().replaceFirstChar { it.uppercase() }}\""
        VoiceState.LISTENING -> Clay to "Listening…"
        VoiceState.SPEAKING -> ClaySoft to "Speaking"
    }
    Row(Modifier.background(Raised, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape)); Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 12.sp, color = Ink)
    }
}

@Composable
fun AssistantScreen(vm: AppViewModel, onMic: () -> Unit, onToggleVoice: () -> Unit, onAttach: () -> Unit, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val count = vm.chat.size + if (vm.busy) 1 else 0
    LaunchedEffect(count, vm.streaming.length / 80) { if (count > 0) list.animateScrollToItem(count - 1) }
    val energy = when { vm.busy -> 0.8f; vm.voiceState == VoiceState.LISTENING || vm.voiceState == VoiceState.SPEAKING -> 1f; else -> 0.15f }
    val empty = vm.chat.isEmpty() && !vm.busy

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Syntra", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.weight(1f))
            VoicePill(vm.voiceState, vm.settings.wakeWords, onToggleVoice)
            IconButton(vm::clearChat) { Icon(Icons.Outlined.DeleteSweep, "Clear conversation", tint = Dim) }
        }

        if (empty) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Orb(energy, Modifier.size(200.dp))
                Spacer(Modifier.height(20.dp))
                Text(vm.heard.ifBlank { "Good to see you." }, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("Speak any time, or type below.", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(28.dp))
                TeamRow(vm.activeRoles, false)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("What's on my screen?", "What do you remember about me?", "Open YouTube", "Research today's AI news").forEach { q ->
                        Text(q, fontSize = 13.sp, color = Ink, modifier = Modifier.border(1.dp, Line, RoundedCornerShape(50))
                            .clickable { vm.send(q) }.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Orb(energy, Modifier.size(40.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { TeamRow(vm.activeRoles, vm.busy) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(vm.chat) { s ->
                    when (s.kind) {
                        StepKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(s.text, Modifier.background(Raised, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(14.dp, 10.dp),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        StepKind.ERROR -> Text(s.text, color = Danger, fontSize = 13.sp)
                        else -> Column {
                            Text(s.who.uppercase(), style = MaterialTheme.typography.labelSmall, color = Clay)
                            Spacer(Modifier.height(4.dp))
                            Text(s.text, style = MaterialTheme.typography.bodyLarge, color = Ink)
                        }
                    }
                }
                if (vm.busy) item {
                    Column {
                        Text("THINKING", style = MaterialTheme.typography.labelSmall, color = Clay)
                        if (vm.status.isNotBlank()) Text(vm.status, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                        if (vm.streaming.isNotBlank()) Text(vm.streaming.replace(Regex("<tool>.*?(</tool>|$)", RegexOption.DOT_MATCHES_ALL), "…"),
                            style = MaterialTheme.typography.bodyLarge, color = Ink.copy(alpha = 0.85f))
                    }
                }
            }
        }

        if (vm.heard.isNotBlank() && !empty) Text("“${vm.heard}”", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))

        vm.pendingApproval?.let { action ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(Raised, RoundedCornerShape(16.dp))
                .border(1.dp, Clay, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Text("Allow this action?", style = MaterialTheme.typography.titleMedium, color = Clay)
                Text(action.take(400), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ink, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ vm.answerApproval(true) }, colors = ButtonDefaults.buttonColors(containerColor = Clay)) { Text("Allow") }
                    OutlinedButton({ vm.answerApproval(false) }) { Text("Deny", color = Ink) }
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

        Row(Modifier.fillMaxWidth().padding(12.dp).background(Panel, RoundedCornerShape(28.dp)).border(1.dp, Line, RoundedCornerShape(28.dp))
            .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onAttach) { Icon(Icons.Outlined.AddPhotoAlternate, "Attach image", tint = Dim) }
            TextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message Syntra", color = Dim) }, maxLines = 4,
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
