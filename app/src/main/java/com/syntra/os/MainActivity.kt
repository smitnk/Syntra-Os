package com.syntra.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.syntra.os.ui.AssistantScreen
import com.syntra.os.ui.Bg
import com.syntra.os.ui.Clay
import com.syntra.os.ui.Dim
import com.syntra.os.ui.MemoryScreen
import com.syntra.os.ui.Panel
import com.syntra.os.ui.Raised
import com.syntra.os.ui.SettingsScreen
import com.syntra.os.ui.SkillsScreen
import com.syntra.os.ui.SyntraTheme
import com.syntra.os.ui.TasksScreen
import com.syntra.os.voice.VoiceLoop
import com.syntra.os.voice.VoiceState
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var voice: VoiceLoop

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && vm.settings.alwaysListening) voice.start()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        val f = File(cacheDir, "attach-${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        vm.attachment = f.path
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var askedMic = false

    private fun startVoice() {
        voice.wakeWords = vm.settings.wakeWords.split(',')
        if (hasMic()) voice.start()
        else if (!askedMic) { askedMic = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) } // once, or deny would loop via onResume
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice = VoiceLoop(this,
            onCommand = { vm.heard = ""; vm.send(it) },
            onState = { s, heard -> vm.voiceState = s; if (s == VoiceState.LISTENING) vm.heard = heard else if (heard.isEmpty() && s != VoiceState.WAITING_WAKE) vm.heard = "" })
        setContent {
            SyntraTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(vm.lastAnswer) {
                    val a = vm.lastAnswer ?: return@LaunchedEffect
                    if (vm.settings.speakReplies) voice.speak(a)
                    vm.lastAnswer = null
                }
                val tabs = listOf(
                    "Syntra" to Icons.Outlined.AutoAwesome, "Memory" to Icons.Outlined.Hub,
                    "Tasks" to Icons.Outlined.TaskAlt, "Skills" to Icons.Outlined.Extension, "Setup" to Icons.Outlined.Settings,
                )
                Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Panel) {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Raised, selectedIconColor = Clay,
                                        selectedTextColor = Clay, unselectedIconColor = Dim, unselectedTextColor = Dim),
                                )
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        0 -> AssistantScreen(vm, onMic = { voice.shutUp(); if (hasMic()) voice.wake() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onToggleVoice = {
                                val on = !vm.settings.alwaysListening
                                vm.updateSettings(vm.settings.copy(alwaysListening = on))
                                if (on) startVoice() else voice.stop()
                            },
                            onAttach = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = m)
                        1 -> MemoryScreen(vm, m)
                        2 -> TasksScreen(vm, m)
                        3 -> SkillsScreen(vm, m)
                        else -> SettingsScreen(vm, m)
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); if (vm.settings.alwaysListening) startVoice() }
    override fun onPause() { voice.stop(); super.onPause() }
    override fun onDestroy() { voice.release(); super.onDestroy() }
}
