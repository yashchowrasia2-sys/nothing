package com.zenith.glyphstudio.engine

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import com.zenith.glyphstudio.data.EngineMode
import com.zenith.glyphstudio.data.GlyphStorage
import com.zenith.glyphstudio.data.MatrixMask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Glyph Toy service (intent action com.nothing.glyph.TOY). Bound by the Nothing Glyph system when the user
 * selects "Glyph Studio HUD" as the active toy. Phone (4a) Pro has no Glyph button → no long-press events;
 * we render continuously and refresh on EVENT_AOD ticks. Mode changes from the app arrive via DataStore.
 */
class GlyphEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gm: GlyphMatrixManager? = null
    private var renderJob: Job? = null
    @Volatile private var lastFrame: IntArray? = null
    @Volatile private var currentMode: EngineMode = EngineMode.Off

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == GlyphToy.MSG_GLYPH_TOY) {
                when (msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)) {
                    GlyphToy.EVENT_AOD -> onAodTick()
                    else -> Unit // EVENT_CHANGE / long-press not available on 4a Pro (no touch button)
                }
            } else super.handleMessage(msg)
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder {
        connect()
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return false
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        if (gm != null) return
        val manager = GlyphMatrixManager.getInstance(applicationContext)
        gm = manager
        manager.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                runCatching { manager.register(Glyph.DEVICE_25111p) }
                    .onFailure { Log.e(TAG, "register failed", it) }
                startRendering()
            }
            override fun onServiceDisconnected(name: ComponentName?) { renderJob?.cancel() }
        })
    }

    private fun startRendering() {
        renderJob?.cancel()
        val storage = GlyphStorage.get(this)
        renderJob = scope.launch {
            storage.mode.collectLatest { mode ->
                currentMode = mode
                GlyphRenderers.frames(this@GlyphEngineService, mode).collect { push(it) }
            }
        }
    }

    /** AOD tick (~1/min): re-push last frame so the matrix stays lit when the system throttles us. */
    private fun onAodTick() {
        val mode = currentMode
        if (mode is EngineMode.Pet) {
            push(MatrixMask.apply(GlyphRenderers.petFrame(GlyphRenderers.mood(this), System.currentTimeMillis() / 150)))
        } else lastFrame?.let(::push)
    }

    private fun push(frame: IntArray) {
        lastFrame = frame
        val m = gm ?: return
        runCatching {
            if (frame.all { it == 0 }) m.turnOff() else m.setMatrixFrame(frame)
        }.onFailure { Log.w(TAG, "setMatrixFrame failed", it) }
    }

    private fun shutdown() {
        renderJob?.cancel(); renderJob = null
        gm?.runCatching { turnOff(); unInit() }
        gm = null
    }

    companion object { private const val TAG = "GlyphEngine" }
}

/**
 * App-side "Live Mirror": while the app is open, pushes frames directly with setAppMatrixFrame
 * (immediate trigger without waiting for the system to bind the toy).
 */
class GlyphAppLink(context: Context) {
    private val ctx = context.applicationContext
    private var gm: GlyphMatrixManager? = null
    @Volatile private var ready = false

    fun open() {
        if (gm != null) return
        val manager = runCatching { GlyphMatrixManager.getInstance(ctx) }.getOrNull() ?: return
        gm = manager
        manager.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                ready = runCatching { manager.register(Glyph.DEVICE_25111p) }.isSuccess
            }
            override fun onServiceDisconnected(name: ComponentName?) { ready = false }
        })
    }

    fun push(frame: IntArray) {
        if (!ready) return
        runCatching { gm?.setAppMatrixFrame(frame) }
    }

    fun close() {
        ready = false
        gm?.runCatching { closeAppMatrix(); unInit() }
        gm = null
    }
}
