package com.syntra.os

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.syntra.os.ui.MemoryScreen
import com.syntra.os.ui.Panel
import com.syntra.os.ui.SettingsScreen
import com.syntra.os.ui.SkillsScreen
import com.syntra.os.ui.SyntraTheme
import com.syntra.os.ui.TasksScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var tts: TextToSpeech? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Syntra")
        runCatching { speech.launch(i) }.onFailure {
            android.widget.Toast.makeText(this, "No speech recognizer on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this) {}
        setContent {
            SyntraTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(vm.lastAnswer) {
                    val a = vm.lastAnswer ?: return@LaunchedEffect
                    if (vm.settings.speakReplies) tts?.speak(a.take(600), TextToSpeech.QUEUE_FLUSH, null, "answer")
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
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Panel),
                                )
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        0 -> AssistantScreen(vm, onMic = ::listen, modifier = m)
                        1 -> MemoryScreen(vm, m)
                        2 -> TasksScreen(vm, m)
                        3 -> SkillsScreen(vm, m)
                        else -> SettingsScreen(vm, m)
                    }
                }
            }
        }
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}
