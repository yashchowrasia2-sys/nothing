package com.zenith.glyphstudio.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.BatteryManager
import com.zenith.glyphstudio.data.CELLS
import com.zenith.glyphstudio.data.CENTER
import com.zenith.glyphstudio.data.EngineMode
import com.zenith.glyphstudio.data.GRID
import com.zenith.glyphstudio.data.MatrixMask
import com.zenith.glyphstudio.data.PixelFont3x5
import com.zenith.glyphstudio.data.PixelFont5x7
import com.zenith.glyphstudio.data.VisualizerStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure frame producers: every mode → Flow<IntArray(169)> of 0..255 brightness. Shared by service + in-app preview. */
object GlyphRenderers {

    fun frames(ctx: Context, mode: EngineMode): Flow<IntArray> = when (mode) {
        EngineMode.Off -> flowOf(IntArray(CELLS))
        is EngineMode.Art -> art(mode)
        is EngineMode.Marquee -> marquee(mode)
        is EngineMode.Visualizer -> visualizer(ctx, mode.style)
        EngineMode.Pet -> pet(ctx)
        is EngineMode.Pomodoro -> pomodoro(mode)
    }.flowOn(Dispatchers.Default)

    // ---------- Art / Animation ----------
    private fun art(m: EngineMode.Art) = flow {
        val frames = m.animation.sanitized().frames.map { MatrixMask.apply(it.toArray()) }
        val period = 1000L / m.animation.fps.coerceIn(1, 12)
        if (frames.size == 1) { emit(frames[0]); return@flow }
        var i = 0
        while (true) { emit(frames[i]); i = (i + 1) % frames.size; delay(period) }
    }

    // ---------- Marquee ----------
    private fun marquee(m: EngineMode.Marquee) = flow {
        val cols = PixelFont5x7.columns(m.text.ifBlank { " " }.take(120))
        val padded = List(GRID) { 0 } + cols + List(GRID) { 0 }
        val top = (GRID - PixelFont5x7.H) / 2
        val level = m.brightness.coerceIn(16, 255)
        var offset = 0
        while (true) {
            val f = IntArray(CELLS)
            for (x in 0 until GRID) {
                val col = padded[(offset + x) % padded.size]
                for (y in 0 until PixelFont5x7.H) if (col and (1 shl y) != 0) f[(top + y) * GRID + x] = level
            }
            emit(MatrixMask.apply(f))
            offset = (offset + 1) % padded.size
            delay(m.stepMs.coerceIn(30, 500))
        }
    }

    // ---------- Pomodoro progress ring ----------
    private fun pomodoro(m: EngineMode.Pomodoro) = flow {
        val total = m.minutes.coerceIn(1, 180) * 60_000L
        val ring = MatrixMask.ring
        while (true) {
            val remaining = (total - (System.currentTimeMillis() - m.startedAt)).coerceAtLeast(0)
            val f = IntArray(CELLS)
            if (remaining == 0L) {
                // Done: blink full ring + "0"
                val on = (System.currentTimeMillis() / 500) % 2 == 0L
                if (on) ring.forEach { f[it] = 255 }
                PixelFont3x5.drawNumber(f, 0, 255)
            } else {
                val frac = remaining.toDouble() / total
                val lit = frac * ring.size
                // Elapsed LEDs dim clockwise from 12 o'clock; the leading LED fades smoothly.
                val dimmed = ring.size - lit
                ring.forEachIndexed { idx, led ->
                    f[led] = when {
                        idx >= dimmed.toInt() + 1 -> 255
                        idx == dimmed.toInt() -> (40 + 215 * (1 - (dimmed - idx))).toInt()
                        else -> 18
                    }
                }
                val mins = ((remaining + 59_999) / 60_000).toInt()
                val readout = if (remaining < 60_000) ((remaining + 999) / 1000).toInt() else mins
                PixelFont3x5.drawNumber(f, readout, 220)
            }
            emit(MatrixMask.apply(f))
            delay(250)
        }
    }

    // ---------- Pocket Pet ----------
    enum class Mood { HAPPY, SLEEPY, SAD, IDLE }

    fun mood(ctx: Context): Mood {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else 100
        } ?: 100
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            charging -> Mood.HAPPY
            level < 20 -> Mood.SAD
            hour >= 23 || hour < 6 -> Mood.SLEEPY
            else -> Mood.IDLE
        }
    }

    private fun pet(ctx: Context) = flow {
        var tick = 0L
        var mood = mood(ctx)
        while (true) {
            if (tick % 40 == 0L) mood = mood(ctx) // re-evaluate every ~6s
            emit(MatrixMask.apply(petFrame(mood, tick)))
            tick++
            delay(150)
        }
    }

    fun petFrame(mood: Mood, tick: Long): IntArray {
        val f = IntArray(CELLS)
        fun p(x: Int, y: Int, v: Int = 255) { if (x in 0 until GRID && y in 0 until GRID) f[y * GRID + x] = v }
        val bob = if (mood == Mood.HAPPY && (tick / 3) % 2 == 0L) -1 else 0
        when (mood) {
            Mood.HAPPY -> {
                // ^ ^ eyes, big grin, sparkles
                listOf(3, 8).forEach { ex -> p(ex, 4 + bob); p(ex + 1, 3 + bob); p(ex + 2, 4 + bob) }
                for (x in 3..9) p(x, 8 + bob); for (x in 4..8) p(x, 9 + bob, 160); p(2, 7 + bob); p(10, 7 + bob)
                val s = (tick % 8).toInt()
                if (s < 4) { p(1, 2, 120 + s * 30); p(11, 2, 240 - s * 30) }
            }
            Mood.SLEEPY -> {
                for (x in 3..5) p(x, 5, 140); for (x in 7..9) p(x, 5, 140)
                for (x in 5..7) p(x, 9, 100)
                val z = (tick / 4 % 3).toInt()
                // floating "z"
                val zx = 9 + z / 2; val zy = 3 - z
                p(zx, zy, 200); p(zx + 1, zy, 200); p(zx, zy + 1, 120)
            }
            Mood.SAD -> {
                p(3, 3, 160); p(9, 3, 160)
                for (y in 4..5) { p(4, y); p(8, y) }
                p(5, 9); p(6, 8); p(7, 9); p(4, 10, 160); p(8, 10, 160)
                val t = ((tick / 2) % 4).toInt()
                p(4, 6 + t, 200 - t * 40)
            }
            Mood.IDLE -> {
                val blink = (tick % 26) in 0..1
                if (blink) { for (x in 3..4) p(x, 5); for (x in 8..9) p(x, 5) }
                else for (y in 4..5) { p(3, y); p(4, y); p(8, y); p(9, y) }
                val look = ((tick / 20) % 4).toInt()
                if (!blink && look == 1) { p(3, 4, 60); p(8, 4, 60) } // glance highlight
                p(4, 8, 180); for (x in 5..7) p(x, 9); p(8, 8, 180)
            }
        }
        return f
    }

    // ---------- Music Visualizer ----------
    private const val BANDS = GRID

    @SuppressLint("MissingPermission")
    private fun visualizer(ctx: Context, style: VisualizerStyle): Flow<IntArray> = callbackFlow {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trySend(MatrixMask.apply(IntArray(CELLS).also { PixelFont3x5.draw(it, 0, 5, 4, 80) }))
            awaitClose { }
            return@callbackFlow
        }
        val smooth = FloatArray(BANDS); val peaks = FloatArray(BANDS)
        fun push(bands: FloatArray) {
            for (i in 0 until BANDS) {
                smooth[i] = if (bands[i] > smooth[i]) bands[i] else smooth[i] * 0.82f + bands[i] * 0.18f
                peaks[i] = maxOf(peaks[i] * 0.94f, smooth[i])
            }
            trySend(if (style == VisualizerStyle.BARS) drawBars(smooth, peaks) else drawRing(smooth))
        }

        var viz: Visualizer? = null
        var record: AudioRecord? = null
        try {
            viz = Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        fft ?: return
                        val n = fft.size / 2
                        val mags = FloatArray(n) { k ->
                            if (k == 0) kotlin.math.abs(fft[0].toFloat()) else hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                        }
                        push(logBands(mags, 1f / 90f))
                    }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(20_000), false, true)
                enabled = true
            }
        } catch (t: Throwable) {
            viz?.release(); viz = null
            // Fallback: microphone AudioRecord + in-house FFT
            val rate = 22_050; val n = 512
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, n * 4)).also { it.startRecording() }
            val rec = record!!
            launch(Dispatchers.IO) {
                val buf = ShortArray(n); val re = FloatArray(n); val im = FloatArray(n)
                while (isActive) {
                    val read = rec.read(buf, 0, n)
                    if (read <= 0) { delay(20); continue }
                    for (i in 0 until n) {
                        val hann = 0.5f - 0.5f * cos(2 * PI * i / (n - 1)).toFloat()
                        re[i] = (if (i < read) buf[i] / 32768f else 0f) * hann; im[i] = 0f
                    }
                    fft(re, im)
                    val mags = FloatArray(n / 2) { hypot(re[it], im[it]) }
                    push(logBands(mags, 1f / 6f))
                    delay(33)
                }
            }
        }
        awaitClose {
            viz?.runCatching { enabled = false; release() }
            record?.runCatching { stop(); release() }
        }
    }.conflate()

    /** Log-spaced band energies normalised to 0..1. */
    private fun logBands(mags: FloatArray, gain: Float): FloatArray {
        val n = mags.size
        val out = FloatArray(BANDS)
        val minBin = 1.0; val maxBin = (n - 1).toDouble()
        for (b in 0 until BANDS) {
            val lo = (minBin * Math.pow(maxBin / minBin, b / BANDS.toDouble())).toInt().coerceIn(1, n - 1)
            val hi = (minBin * Math.pow(maxBin / minBin, (b + 1) / BANDS.toDouble())).toInt().coerceIn(lo + 1, n)
            var sum = 0f
            for (k in lo until hi) sum += mags[k]
            val avg = sum / (hi - lo)
            out[b] = (ln(1f + avg * gain * (1 + b * 0.25f)) / ln(4f)).coerceIn(0f, 1f)
        }
        return out
    }

    private fun drawBars(v: FloatArray, peaks: FloatArray): IntArray {
        val f = IntArray(CELLS)
        for (x in 0 until GRID) {
            val col = (0 until GRID).filter { MatrixMask.isLed(x, it) }
            if (col.isEmpty()) continue
            val h = (v[x] * col.size).toInt()
            val bottom = col.max()
            for (i in 0 until h) f[(bottom - i) * GRID + x] = 120 + 135 * i / col.size
            val pk = (peaks[x] * col.size).toInt().coerceIn(0, col.size - 1)
            if (pk > 0) f[(bottom - pk) * GRID + x] = 255
        }
        return MatrixMask.apply(f)
    }

    private fun drawRing(v: FloatArray): IntArray {
        val f = IntArray(CELLS)
        val bass = (v[0] + v[1] + v[2]) / 3f
        val treble = v.takeLast(5).average().toFloat()
        val radius = 1.5f + bass * 4.8f
        for (i in 0 until CELLS) {
            val dx = i % GRID - CENTER.toFloat(); val dy = i / GRID - CENTER.toFloat()
            val d = sqrt(dx * dx + dy * dy)
            val ring = 1f - kotlin.math.abs(d - radius) / 1.2f
            if (ring > 0) f[i] = (ring * 255).toInt()
            if (d < 1.2f) f[i] = (80 + treble * 175).toInt()
        }
        // sparkles on angular band peaks
        for (b in 0 until BANDS) if (v[b] > 0.75f) {
            val a = 2 * PI * b / BANDS
            val x = (CENTER + 6 * sin(a)).toInt(); val y = (CENTER - 6 * cos(a)).toInt()
            if (MatrixMask.isLed(x, y)) f[y * GRID + x] = 255
        }
        return MatrixMask.apply(f)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
