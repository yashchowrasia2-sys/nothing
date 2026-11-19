package com.zenith.glyphstudio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zenith.glyphstudio.data.BuiltInPresets
import com.zenith.glyphstudio.data.CELLS
import com.zenith.glyphstudio.data.PresetCategory
import com.zenith.glyphstudio.data.EngineMode
import com.zenith.glyphstudio.data.GlyphAnimation
import com.zenith.glyphstudio.data.GlyphFrame
import com.zenith.glyphstudio.data.GlyphStorage
import com.zenith.glyphstudio.data.MAX_FRAMES
import com.zenith.glyphstudio.data.MatrixMask
import com.zenith.glyphstudio.data.VisualizerStyle
import com.zenith.glyphstudio.engine.GlyphAppLink
import com.zenith.glyphstudio.engine.GlyphRenderers
import com.zenith.glyphstudio.update.AppUpdater
import com.zenith.glyphstudio.update.UpdateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = GlyphStorage.get(app)
    private val updater = AppUpdater(app)
    private val link = GlyphAppLink(app)

    // ---- Studio state ----
    private val _anim = MutableStateFlow(GlyphAnimation(name = "My Glyph"))
    val anim: StateFlow<GlyphAnimation> = _anim.asStateFlow()
    private val _frameIndex = MutableStateFlow(0)
    val frameIndex: StateFlow<Int> = _frameIndex.asStateFlow()
    val brush = MutableStateFlow(255)
    val eraser = MutableStateFlow(false)
    val onionSkin = MutableStateFlow(true)
    val liveMirror = MutableStateFlow(false)

    // ---- Engine / gallery ----
    val mode: StateFlow<EngineMode> = storage.mode.stateIn(viewModelScope, SharingStarted.Eagerly, EngineMode.Off)
    val presets: StateFlow<List<GlyphAnimation>> = storage.presets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Live render of the active engine mode for on-screen preview (and optional Live Mirror to hardware). */
    val preview: StateFlow<IntArray> = mode
        .flatMapLatest { GlyphRenderers.frames(getApplication(), it) }
        .onEach { if (liveMirror.value) link.push(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), IntArray(CELLS))

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { storage.draft.firstOrNull()?.let { _anim.value = it.sanitized() } }
        // Autosave draft (debounced)
        viewModelScope.launch { _anim.drop(1).debounce(600).collect { storage.saveDraft(it) } }
        viewModelScope.launch { liveMirror.collect { if (it) link.open() else link.close() } }
    }

    // ---------- Drawing ----------
    private fun editFrame(block: (GlyphFrame) -> GlyphFrame) = _anim.update { a ->
        val i = _frameIndex.value.coerceIn(0, a.frames.lastIndex)
        a.copy(frames = a.frames.toMutableList().also { it[i] = block(it[i]) })
    }

    fun paint(index: Int) {
        if (index !in 0 until CELLS || !MatrixMask.mask[index]) return
        val v = if (eraser.value) 0 else brush.value
        if (_anim.value.frames[_frameIndex.value].px[index] == v) return
        editFrame { it.set(index, v) }
    }
    fun clear() = editFrame { GlyphFrame() }
    fun invert() = editFrame { it.invert(brush.value) }
    fun mirror() = editFrame { it.mirrorH() }

    // ---------- Frames ----------
    fun selectFrame(i: Int) { _frameIndex.value = i.coerceIn(0, _anim.value.frames.lastIndex) }
    fun addFrame() = _anim.update { a ->
        if (a.frames.size >= MAX_FRAMES) a else a.copy(frames = a.frames + GlyphFrame()).also { _frameIndex.value = it.frames.lastIndex }
    }
    fun duplicateFrame() = _anim.update { a ->
        if (a.frames.size >= MAX_FRAMES) a else {
            val i = _frameIndex.value
            a.copy(frames = a.frames.toMutableList().also { it.add(i + 1, it[i]) }).also { _frameIndex.value = i + 1 }
        }
    }
    fun deleteFrame() = _anim.update { a ->
        if (a.frames.size <= 1) a.copy(frames = listOf(GlyphFrame())) else {
            val i = _frameIndex.value
            a.copy(frames = a.frames.toMutableList().also { it.removeAt(i) }).also { _frameIndex.value = (i - 1).coerceAtLeast(0) }
        }
    }
    fun setFps(fps: Int) = _anim.update { it.copy(fps = fps.coerceIn(1, 12)) }
    fun rename(name: String) = _anim.update { it.copy(name = name.take(40)) }
    fun newCanvas() { _anim.value = GlyphAnimation(name = "My Glyph"); _frameIndex.value = 0 }

    // ---------- Engine triggers ----------
    private fun setMode(m: EngineMode) = viewModelScope.launch { storage.setMode(m) }
    fun playArt() = setMode(EngineMode.Art(_anim.value.sanitized()))
    fun playMarquee(text: String, stepMs: Long, brightness: Int) = setMode(EngineMode.Marquee(text.take(120), stepMs, brightness))
    fun startVisualizer(style: VisualizerStyle) = setMode(EngineMode.Visualizer(style))
    fun startPet() = setMode(EngineMode.Pet)
    fun startTiltGame() = setMode(EngineMode.TiltGame)
    fun startPomodoro(minutes: Int) = setMode(EngineMode.Pomodoro(minutes, System.currentTimeMillis()))
    fun stop() = setMode(EngineMode.Off)

    // ---------- Gallery ----------
    fun saveCurrent() = viewModelScope.launch {
        val a = _anim.value.let { if (" · " in it.name) it else it.copy(name = "${PresetCategory.MINE.prefix} · ${it.name}") }
        // built-ins are read-only: saving an edited built-in creates a personal copy
        val toSave = if (BuiltInPresets.isBuiltIn(a)) a.copy(id = System.currentTimeMillis().toString(36)) else a
        _anim.value = toSave
        storage.savePreset(toSave); message.value = "Saved \"${toSave.name}\""
    }
    fun load(p: GlyphAnimation) { _anim.value = p.sanitized(); _frameIndex.value = 0; message.value = "Loaded into Studio" }
    fun apply(p: GlyphAnimation) = setMode(EngineMode.Art(p.sanitized()))
    fun delete(p: GlyphAnimation) = viewModelScope.launch { storage.deletePreset(p.id) }
    fun exportCode(p: GlyphAnimation = _anim.value): String = GlyphStorage.exportCode(p)
    fun importCode(code: String) {
        GlyphStorage.importCode(code)
            .onSuccess { a -> viewModelScope.launch { storage.savePreset(a) }; load(a); message.value = "Imported \"${a.name}\"" }
            .onFailure { message.value = "Invalid code: ${it.message}" }
    }

    // ---------- Updates ----------
    fun checkUpdate() = viewModelScope.launch { _update.value = UpdateState.Checking; _update.value = updater.check() }
    fun downloadUpdate() {
        val s = _update.value as? UpdateState.Available ?: return
        viewModelScope.launch { updater.download(s.info).collect { _update.value = it } }
    }
    fun updaterRef() = updater

    override fun onCleared() { link.close(); super.onCleared() }
}
