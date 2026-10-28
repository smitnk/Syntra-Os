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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "crash.txt").writeText(e.stackTraceToString().take(4000)) }
            previous?.uncaughtException(t, e)
        }
        enableEdgeToEdge()
        voice = VoiceLoop(this,
            onCommand = { vm.heard = ""; vm.send(it); if (!vm.settings.alwaysListening) voice.stop() }, // chat mode: push-to-talk only
            onState = { s, heard -> vm.voiceState = s; if (s == VoiceState.LISTENING) vm.heard = heard else if (heard.isEmpty() && s != VoiceState.WAITING_WAKE) vm.heard = "" })
        voice.onLevel = { vm.micLevel = it }
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
                            onToggleVoice = { on ->
                                vm.updateSettings(vm.settings.copy(alwaysListening = on, speakReplies = on))
                                if (on) startVoice() else { voice.stop(); voice.shutUp() }
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

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onResume() {
        super.onResume()
        vm.reload()
        // Ask for notifications once, after the mic prompt has had its turn (two dialogs can't overlap).
        if (android.os.Build.VERSION.SDK_INT >= 33 && hasMic() && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (vm.settings.alwaysListening) startVoice()
    }
    private var askedNotif = false
    override fun onPause() { voice.stop(); super.onPause() }
    override fun onDestroy() { voice.release(); super.onDestroy() }
}
