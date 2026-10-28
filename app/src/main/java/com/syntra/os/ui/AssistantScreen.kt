package com.syntra.os.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntra.os.AppViewModel
import com.syntra.os.core.StepKind

@Composable
fun Orb(active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 2500 else 9000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.55f), Violet.copy(alpha = 0.15f), Bg), radius = r * pulse), r * pulse)
        listOf(0.95f to 3f, 0.75f to 2f, 0.55f to 1.5f).forEachIndexed { i, (k, w) ->
            val sweep = if (i % 2 == 0) 250f else 160f
            drawArc(Cyan.copy(alpha = 0.8f - i * 0.2f), spin * (if (i % 2 == 0) 1 else -1) + i * 40, sweep, false,
                topLeft = center.copy(center.x - r * k, center.y - r * k),
                size = androidx.compose.ui.geometry.Size(r * k * 2, r * k * 2), style = Stroke(w.dp.toPx()))
        }
        drawCircle(Cyan, r * 0.12f * pulse)
    }
}

@Composable
fun AssistantScreen(vm: AppViewModel, onMic: () -> Unit, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.chat.size) { if (vm.chat.isNotEmpty()) list.animateScrollToItem(vm.chat.size - 1) }
    val slot = vm.settings.slot(vm.settings.primaryRole)

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Orb(vm.busy, Modifier.size(if (vm.chat.isEmpty()) 64.dp else 44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SYNTRA", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace)
                Text(if (vm.busy) vm.status.ifBlank { "Thinking…" } else "${slot.model} · ${vm.settings.primaryRole.label}",
                    color = Dim, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = vm::clearChat) { Icon(Icons.Outlined.DeleteSweep, "Clear chat", tint = Dim) }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.chat.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Orb(false, Modifier.size(160.dp))
                    Spacer(Modifier.size(16.dp))
                    Text("Online. How can I help?", color = Cyan, fontSize = 18.sp)
                    Text("Tap the mic or type. Set a provider in Setup first.", color = Dim, fontSize = 13.sp)
                }
            }
            items(vm.chat) { s ->
                val mine = s.kind == StepKind.USER
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.fillMaxWidth(0.88f).background(
                        when (s.kind) { StepKind.USER -> Violet.copy(alpha = 0.22f); StepKind.ERROR -> Danger.copy(alpha = 0.15f); else -> Panel },
                        RoundedCornerShape(14.dp)).padding(12.dp)) {
                        Column {
                            if (!mine) Text(s.who, color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(s.text, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        vm.pendingApproval?.let { action ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.12f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Approval needed", color = Danger, fontWeight = FontWeight.Bold)
                    Text(action.take(400), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.answerApproval(true) }, colors = ButtonDefaults.buttonColors(containerColor = Ok)) { Text("Allow") }
                        OutlinedButton({ vm.answerApproval(false) }) { Text("Deny") }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask or command…") },
                shape = RoundedCornerShape(24.dp), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            when {
                vm.busy -> FilledIconButton(vm::cancel, Modifier.size(52.dp)) { Icon(Icons.Outlined.Stop, "Stop") }
                input.isBlank() -> FilledIconButton(onMic, Modifier.size(52.dp), shape = CircleShape) { Icon(Icons.Outlined.Mic, "Speak") }
                else -> FilledIconButton({ vm.send(input); input = "" }, Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            }
        }
    }
}
