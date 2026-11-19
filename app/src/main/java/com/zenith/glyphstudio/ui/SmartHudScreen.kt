package com.zenith.glyphstudio.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenith.glyphstudio.MainViewModel
import com.zenith.glyphstudio.data.EngineMode
import com.zenith.glyphstudio.data.VisualizerStyle

@Composable
fun SmartHudScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val mode by vm.mode.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val mirror by vm.liveMirror.collectAsStateWithLifecycle()
    var vizStyle by rememberSaveable { mutableStateOf(VisualizerStyle.BARS) }
    var pomodoroMin by rememberSaveable { mutableFloatStateOf(25f) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVisualizer(vizStyle) else vm.message.value = "Microphone permission needed for the visualizer"
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Live preview of whatever the engine is rendering
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Now: ${mode.label()}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                MatrixCanvas(preview, Modifier.size(220.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Mirror to Glyph")
                    Spacer(Modifier.width(8.dp))
                    Switch(mirror, { vm.liveMirror.value = it })
                }
                Text(
                    "Select \"Glyph Studio HUD\" as your Glyph Toy for always-on / AOD rendering.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(vm::stop) { Icon(Icons.Default.Stop, null); Text(" Stop / Off") }
            }
        }

        HudCard("Music Visualizer", Icons.Default.GraphicEq) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualizerStyle.entries.forEach { s ->
                    FilterChip(vizStyle == s, { vizStyle = s }, label = { Text(if (s == VisualizerStyle.BARS) "EQ Bars" else "Pulse Ring") })
                }
            }
            Button({
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVisualizer(vizStyle)
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text("Start Visualizer") }
        }

        HudCard("Pocket Pet", Icons.Default.Pets) {
            Text("Happy when charging · sleepy 11 PM–6 AM · sad under 20% · idle blinking otherwise.",
                style = MaterialTheme.typography.bodySmall)
            Button(vm::startPet) { Text("Wake Pet") }
        }

        HudCard("Pomodoro Ring", Icons.Default.Timer) {
            Text("Focus length: ${pomodoroMin.toInt()} min")
            Slider(pomodoroMin, { pomodoroMin = it }, valueRange = 5f..60f, steps = 10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.startPomodoro(pomodoroMin.toInt()) }) { Text("Start") }
                if (mode is EngineMode.Pomodoro) OutlinedButton(vm::stop) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun HudCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

fun EngineMode.label(): String = when (this) {
    EngineMode.Off -> "Off"
    is EngineMode.Art -> "Art · ${animation.name}"
    is EngineMode.Marquee -> "Marquee · \"${text.take(16)}\""
    is EngineMode.Visualizer -> "Visualizer · ${style.name.lowercase()}"
    EngineMode.Pet -> "Pocket Pet"
    is EngineMode.Pomodoro -> "Pomodoro · $minutes min"
}
