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
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.unit.sp
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
        if (savedInstanceState == null) vm.startTelegram()
        startForegroundService(android.content.Intent(this, SyntraService::class.java)) // keeps the AI core, local API and Linux alive
        setContent {
            SyntraTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(vm.lastAnswer) {
                    val a = vm.lastAnswer ?: return@LaunchedEffect
                    if (vm.settings.speakReplies) voice.speak(a)
                    vm.lastAnswer = null
                }
                // 4 primary destinations (M3: bar ≤5) + drawer for Models and Settings.
                val tabs = listOf(
                    "Chat" to Icons.Outlined.AutoAwesome, "Tasks" to Icons.Outlined.TaskAlt,
                    "Workspace" to Icons.Outlined.Terminal, "Extensions" to Icons.Outlined.Extension,
                )
                val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var page by rememberSaveable { mutableIntStateOf(-1) } // -1 = tabs, 0 = Models, 1 = Settings
                androidx.activity.compose.BackHandler(enabled = page >= 0) { page = -1 }
                androidx.compose.material3.ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                    androidx.compose.material3.ModalDrawerSheet(drawerContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text("Syntra OS", style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp))
                        listOf(Triple("Models", Icons.Outlined.Memory, 0), Triple("Settings", Icons.Outlined.Settings, 1)).forEach { (l, ic, i) ->
                            androidx.compose.material3.NavigationDrawerItem(label = { Text(l) }, icon = { Icon(ic, null) }, selected = page == i,
                                onClick = { page = i; scope.launch { drawer.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }) {
                Scaffold(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    topBar = {
                        if (page >= 0) androidx.compose.material3.TopAppBar(
                            title = { Text(if (page == 0) "Models" else "Settings") },
                            navigationIcon = { androidx.compose.material3.IconButton({ page = -1 }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
                    },
                    bottomBar = {
                        if (page < 0) NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer) {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = null) }, label = { Text(label, maxLines = 1) })
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when {
                        page == 0 -> com.syntra.os.ui.ModelsScreen(vm, m)
                        page == 1 -> SettingsScreen(vm, m)
                        tab == 0 -> AssistantScreen(vm, onMic = { voice.shutUp(); if (hasMic()) voice.wake() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onToggleVoice = { on ->
                                vm.updateSettings(vm.settings.copy(alwaysListening = on, speakReplies = on))
                                if (on) startVoice() else { voice.stop(); voice.shutUp() }
                            },
                            onAttach = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onMenu = { scope.launch { drawer.open() } },
                            modifier = m)
                        tab == 1 -> TasksScreen(vm, m)
                        tab == 2 -> com.syntra.os.ui.WorkspaceScreen(vm, m)
                        else -> SkillsScreen(vm, m)
                    }
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
