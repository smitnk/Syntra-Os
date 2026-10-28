package com.syntra.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntra.os.AppViewModel
import com.syntra.os.device.Linux

@Composable
fun LinuxScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var cmd by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.linuxLines.size) { if (vm.linuxLines.isNotEmpty()) list.scrollToItem(vm.linuxLines.size - 1) }

    Column(modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Linux", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text("Ubuntu 24.04 inside Syntra. ai, Hermes and Aider here use Syntra's local models (or cloud).", color = Dim, fontSize = 12.sp)
            }
            if (vm.linuxInstalled) {
                androidx.compose.material3.TextButton(vm::linuxReprobe) { Text("Repair", color = Clay) }
                IconButton(vm::linuxRestart) { Icon(Icons.Outlined.RestartAlt, "Restart shell", tint = Dim) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            !Linux.bundled(ctx) -> Text("This build doesn't include proot. Install the APK from GitHub Actions.", color = Danger)
            !vm.linuxInstalled -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("One-time download, about 30 MB. After that it works offline (apt needs internet).", color = Dim, fontSize = 13.sp)
                Button(vm::installLinux, colors = ButtonDefaults.buttonColors(containerColor = Clay)) { Text("Install Ubuntu", color = Bg) }
                if (vm.linuxStatus.isNotBlank()) Text(vm.linuxStatus, color = Dim, fontSize = 13.sp)
            }
            else -> {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0D0B0A), RoundedCornerShape(12.dp))
                    .border(1.dp, Line, RoundedCornerShape(12.dp)), state = list, contentPadding = PaddingValues(12.dp)) {
                    if (vm.linuxLines.isEmpty()) item { Text("Shell ready. Try: apt update && apt install -y python3 git", color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    items(vm.linuxLines) { l ->
                        Text(l, color = if (l.startsWith("$ ")) Clay else Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Install", color = Dim, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Linux.INSTALLERS.forEach { (label, script) ->
                        Text(label, fontSize = 12.sp, color = Bg, modifier = Modifier.background(Clay, RoundedCornerShape(50))
                            .clickable { vm.linuxSend(script) }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ai \"write a python hello world\"", "hermes -q \"what can you do?\" -Q", "ls -la", "apt update", "apt install -y python3", "python3 --version", "uname -a", "df -h").forEach { q ->
                        Text(q, fontSize = 12.sp, color = Ink, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(50)).clickable { vm.linuxSend(q) }
                                .padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextField(cmd, { cmd = it }, Modifier.weight(1f), placeholder = { Text("command", color = Dim, fontFamily = FontFamily.Monospace) },
                        singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (cmd.isNotBlank()) { vm.linuxSend(cmd); cmd = "" } }),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    IconButton({ if (cmd.isNotBlank()) { vm.linuxSend(cmd); cmd = "" } }) { Icon(Icons.AutoMirrored.Outlined.Send, "Run", tint = Clay) }
                }
            }
        }
    }
}

