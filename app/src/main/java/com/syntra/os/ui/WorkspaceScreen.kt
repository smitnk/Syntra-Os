package com.syntra.os.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.syntra.os.AppViewModel

/** Workspace = where work happens: the terminal and the knowledge/memory graph. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = sub) {
            listOf("Terminal", "Memory").forEachIndexed { i, l -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(l) }) }
        }
        if (sub == 0) LinuxScreen(vm, Modifier.weight(1f)) else MemoryScreen(vm, Modifier.weight(1f))
    }
}
