package com.zenith.glyphstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenith.glyphstudio.MainViewModel
import com.zenith.glyphstudio.data.MAX_FRAMES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: MainViewModel) {
    val anim by vm.anim.collectAsStateWithLifecycle()
    val idx by vm.frameIndex.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val eraser by vm.eraser.collectAsStateWithLifecycle()
    val onion by vm.onionSkin.collectAsStateWithLifecycle()
    val frame = anim.frames[idx.coerceIn(0, anim.frames.lastIndex)].toArray()
    val prev = if (onion && idx > 0) anim.frames[idx - 1].toArray() else null

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(anim.name, vm::rename, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        MatrixCanvas(frame, Modifier.fillMaxWidth(), onion = prev, onPaint = vm::paint)

        // Tools
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!eraser, { vm.eraser.value = false }, label = { Text("Draw") }, leadingIcon = { Icon(Icons.Default.Brush, null) })
            FilterChip(eraser, { vm.eraser.value = true }, label = { Text("Erase") }, leadingIcon = { Icon(Icons.Default.AutoFixOff, null) })
            FilterChip(onion, { vm.onionSkin.value = !onion }, label = { Text("Onion") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(vm::clear) { Text("Clear") }
            OutlinedButton(vm::invert) { Text("Invert") }
            OutlinedButton(vm::mirror) { Icon(Icons.Default.Flip, null); Text(" Mirror") }
        }
        Text("Brightness: $brush")
        Slider(brush.toFloat(), { vm.brush.value = it.toInt() }, valueRange = 1f..255f)

        // Frames
        HorizontalDivider()
        Text("Frames (${anim.frames.size}/$MAX_FRAMES)", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(anim.frames) { i, f ->
                Card(
                    onClick = { vm.selectFrame(i) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (i == idx) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MatrixCanvas(f.toArray(), Modifier.size(56.dp))
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(vm::addFrame, enabled = anim.frames.size < MAX_FRAMES) { Icon(Icons.Default.Add, null) }
            FilledTonalButton(vm::duplicateFrame, enabled = anim.frames.size < MAX_FRAMES) { Icon(Icons.Default.ContentCopy, null) }
            FilledTonalButton(vm::deleteFrame) { Icon(Icons.Default.Delete, null) }
        }
        Text("Speed: ${anim.fps} FPS")
        Slider(anim.fps.toFloat(), { vm.setFps(it.toInt()) }, valueRange = 1f..12f, steps = 10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(vm::playArt) { Icon(Icons.Default.PlayArrow, null); Text(" Send to Glyph") }
            OutlinedButton(vm::saveCurrent) { Icon(Icons.Default.Save, null); Text(" Save") }
            TextButton(vm::newCanvas) { Text("New") }
        }

        // Marquee
        HorizontalDivider()
        MarqueeSection(vm)
    }
}

@Composable
private fun MarqueeSection(vm: MainViewModel) {
    var text by rememberSaveable { mutableStateOf("BUSY") }
    var speed by rememberSaveable { mutableFloatStateOf(90f) }
    var level by rememberSaveable { mutableFloatStateOf(255f) }
    Text("LED Text Marquee", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        text, { text = it.take(120) }, label = { Text("Text (A–Z, 0–9, !?.:-+/#@♥)") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Text("Scroll step: ${speed.toInt()} ms")
    Slider(speed, { speed = it }, valueRange = 40f..300f)
    Text("Brightness: ${level.toInt()}")
    Slider(level, { level = it }, valueRange = 16f..255f)
    Button({ vm.playMarquee(text, speed.toLong(), level.toInt()) }, enabled = text.isNotBlank()) {
        Icon(Icons.Default.TextFields, null); Text(" Scroll on Glyph")
    }
}
