package com.zenith.glyphstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.zenith.glyphstudio.BuildConfig
import com.zenith.glyphstudio.MainViewModel
import com.zenith.glyphstudio.update.UpdateState

@Composable
fun GalleryScreen(vm: MainViewModel, onOpenStudio: () -> Unit) {
    val ctx = LocalContext.current
    val presets by vm.presets.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var importText by rememberSaveable { mutableStateOf("") }
    val clipboard = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    fun copy(code: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Glyph code", code))
        vm.message.value = "Share code copied"
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Share Codes", style = MaterialTheme.typography.titleMedium)
                    Button({ copy(vm.exportCode()) }) { Icon(Icons.Default.Share, null); Text(" Export current Studio design") }
                    OutlinedTextField(importText, { importText = it.take(64_000) }, label = { Text("Paste GLY1: code") },
                        maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ importText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() }) {
                            Icon(Icons.Default.ContentPaste, null); Text(" Paste")
                        }
                        Button({ vm.importCode(importText); importText = ""; onOpenStudio() }, enabled = importText.isNotBlank()) {
                            Text("Import")
                        }
                    }
                }
            }
        }

        item { Text("Saved Presets (${presets.size})", style = MaterialTheme.typography.titleMedium) }
        if (presets.isEmpty()) item { Text("Nothing saved yet — create something in Studio.", style = MaterialTheme.typography.bodySmall) }
        items(presets.reversed(), key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MatrixCanvas(p.frames.first().toArray(), Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text("${p.frames.size} frame(s) · ${p.fps} FPS", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ vm.apply(p) }) { Icon(Icons.Default.PlayArrow, "Apply") }
                    IconButton({ vm.load(p); onOpenStudio() }) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton({ copy(vm.exportCode(p)) }) { Icon(Icons.Default.Share, "Export") }
                    IconButton({ vm.delete(p) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }

        item { UpdateCard(vm, update) }
    }
}

@Composable
private fun UpdateCard(vm: MainViewModel, state: UpdateState) {
    val ctx = LocalContext.current
    val updater = vm.updaterRef()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App Updates", style = MaterialTheme.typography.titleMedium)
            Text("Installed: v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            when (state) {
                UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Error -> {
                    if (state == UpdateState.UpToDate) Text("You're on the latest version.")
                    if (state is UpdateState.Error) Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(vm::checkUpdate) { Icon(Icons.Default.SystemUpdate, null); Text(" Check GitHub Releases") }
                }
                UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is UpdateState.Available -> {
                    Text("v${state.info.version} available (${state.info.size / 1024} KB)")
                    if (state.info.notes.isNotBlank()) Text(state.info.notes.take(400), style = MaterialTheme.typography.bodySmall)
                    Button(vm::downloadUpdate) { Icon(Icons.Default.Download, null); Text(" Download") }
                }
                is UpdateState.Downloading -> {
                    LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}%")
                }
                is UpdateState.ReadyToInstall -> Button({
                    if (updater.canInstall()) ctx.startActivity(updater.installIntent(state.file))
                    else {
                        vm.message.value = "Allow installs from Glyph Studio, then tap Install again"
                        ctx.startActivity(updater.unknownSourcesIntent())
                    }
                }) { Icon(Icons.Default.InstallMobile, null); Text(" Install update") }
            }
        }
    }
}
