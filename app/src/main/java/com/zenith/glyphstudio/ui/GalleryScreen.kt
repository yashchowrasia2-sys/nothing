package com.zenith.glyphstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenith.glyphstudio.BuildConfig
import com.zenith.glyphstudio.MainViewModel
import com.zenith.glyphstudio.data.BuiltInPresets
import com.zenith.glyphstudio.data.EngineMode
import com.zenith.glyphstudio.data.GlyphAnimation
import com.zenith.glyphstudio.data.PresetCategory
import com.zenith.glyphstudio.update.UpdateState
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(vm: MainViewModel, onOpenStudio: () -> Unit) {
    val ctx = LocalContext.current
    val mine by vm.presets.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val activeId = (mode as? EngineMode.Art)?.animation?.id
    var category by rememberSaveable { mutableStateOf(PresetCategory.HERO) }
    var importText by rememberSaveable { mutableStateOf("") }
    val clipboard = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    fun copy(code: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Glyph code", code))
        vm.message.value = "Share code copied"
    }

    val items: List<GlyphAnimation> =
        if (category == PresetCategory.MINE) mine.reversed() else BuiltInPresets.all[category].orEmpty()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("LIBRARY", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PresetCategory.entries) { c ->
                    val count = if (c == PresetCategory.MINE) mine.size else BuiltInPresets.all[c]?.size ?: 0
                    FilterChip(
                        selected = c == category, onClick = { category = c },
                        label = { Text("${c.title} · $count") },
                    )
                }
            }
        }

        item {
            if (items.isEmpty()) {
                Text("Nothing saved yet — draw in Studio and tap Save.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Tap to send to Glyph · long-press to edit in Studio",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 3,
                ) {
                    items.forEach { p ->
                        PresetTile(
                            p, active = p.id == activeId, modifier = Modifier.weight(1f),
                            onApply = { vm.apply(p); vm.message.value = "${p.name.substringAfter(" · ")} → Glyph" },
                            onEdit = { vm.load(p); onOpenStudio() },
                            onShare = { copy(vm.exportCode(p)) },
                            onDelete = if (category == PresetCategory.MINE) ({ vm.delete(p) }) else null,
                        )
                    }
                    // keep last row aligned
                    repeat((3 - items.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        item {
            SectionCard("Share Codes", Icons.Default.QrCode2) {
                Button({ copy(vm.exportCode()) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Export current Studio design")
                }
                OutlinedTextField(importText, { importText = it.take(64_000) }, label = { Text("Paste GLY1: code") },
                    maxLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ importText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() }) {
                        Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste")
                    }
                    Button({ vm.importCode(importText); importText = ""; onOpenStudio() }, enabled = importText.isNotBlank()) {
                        Text("Import")
                    }
                }
            }
        }

        item { UpdateCard(vm, update) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    p: GlyphAnimation, active: Boolean, modifier: Modifier,
    onApply: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onDelete: (() -> Unit)?,
) {
    // Animated thumbnail for multi-frame presets
    var frame by remember(p.id) { mutableIntStateOf(0) }
    if (p.frames.size > 1) LaunchedEffect(p.id) {
        while (true) { delay(1000L / p.fps); frame = (frame + 1) % p.frames.size }
    }
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (active) 2.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.combinedClickable(onClick = onApply, onLongClick = onEdit).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MatrixCanvas(p.frames[frame.coerceIn(0, p.frames.lastIndex)].toArray(), Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name.substringAfter(" · "), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    IconButton({ menu = true }, Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, "More", Modifier.size(16.dp)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Edit in Studio") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem({ Text("Copy share code") }, { menu = false; onShare() }, leadingIcon = { Icon(Icons.Default.Share, null) })
                        if (onDelete != null) DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
            }
            if (p.frames.size > 1) Text("${p.frames.size}f · ${p.fps}fps", style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun UpdateCard(vm: MainViewModel, state: UpdateState) {
    val ctx = LocalContext.current
    val updater = vm.updaterRef()
    SectionCard("App Updates", Icons.Default.SystemUpdate) {
        Text("Installed: v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        when (state) {
            UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Error -> {
                if (state == UpdateState.UpToDate) Text("You're on the latest version.")
                if (state is UpdateState.Error) Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(vm::checkUpdate) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Check GitHub Releases") }
            }
            UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UpdateState.Available -> {
                Text("v${state.info.version} available (${state.info.size / 1024} KB)")
                if (state.info.notes.isNotBlank()) Text(state.info.notes.take(400), style = MaterialTheme.typography.bodySmall)
                Button(vm::downloadUpdate) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Download") }
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
            }) { Icon(Icons.Default.InstallMobile, null); Spacer(Modifier.width(8.dp)); Text("Install update") }
        }
    }
}
