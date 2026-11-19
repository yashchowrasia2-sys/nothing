package com.zenith.glyphstudio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.atan2

const val GRID = 13
const val CELLS = GRID * GRID
const val MAX_FRAMES = 8
const val CENTER = 6

/** Circular 13x13 mask → exactly 137 physical LEDs on Phone (4a) Pro (dx²+dy² ≤ 41). */
object MatrixMask {
    val mask: BooleanArray = BooleanArray(CELLS) { i ->
        val dx = i % GRID - CENTER; val dy = i / GRID - CENTER
        dx * dx + dy * dy <= 41
    }
    val ledCount: Int = mask.count { it }

    fun isLed(x: Int, y: Int): Boolean = x in 0 until GRID && y in 0 until GRID && mask[y * GRID + x]

    /** Outer ring LEDs ordered clockwise starting at 12 o'clock. */
    val ring: List<Int> = (0 until CELLS).filter { i ->
        if (!mask[i]) return@filter false
        val x = i % GRID; val y = i / GRID
        listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (nx, ny) -> !isLed(nx, ny) }
    }.sortedBy { i ->
        val dx = (i % GRID - CENTER).toDouble(); val dy = (i / GRID - CENTER).toDouble()
        val a = atan2(dx, -dy) // 0 at top, increases clockwise
        if (a < 0) a + 2 * Math.PI else a
    }

    fun apply(frame: IntArray): IntArray = IntArray(CELLS) { if (mask[it]) frame[it].coerceIn(0, 255) else 0 }
}

@Serializable
data class GlyphFrame(val px: List<Int> = List(CELLS) { 0 }) {
    init { require(px.size == CELLS) { "Frame must have $CELLS pixels" } }

    fun toArray(): IntArray = px.toIntArray()
    fun set(index: Int, value: Int) = copy(px = px.toMutableList().also { it[index] = value.coerceIn(0, 255) })
    fun invert(level: Int) = copy(px = px.mapIndexed { i, v -> if (!MatrixMask.mask[i]) 0 else if (v > 0) 0 else level })
    fun mirrorH() = copy(px = List(CELLS) { i -> px[(i / GRID) * GRID + (GRID - 1 - i % GRID)] })

    companion object {
        fun of(arr: IntArray) = GlyphFrame(MatrixMask.apply(arr).toList())
    }
}

@Serializable
data class GlyphAnimation(
    val id: String = System.currentTimeMillis().toString(36),
    val name: String = "Untitled",
    val frames: List<GlyphFrame> = listOf(GlyphFrame()),
    val fps: Int = 6,
) {
    /** Clamp & sanitise untrusted (imported) data. */
    fun sanitized(): GlyphAnimation = copy(
        name = name.take(40),
        frames = frames.take(MAX_FRAMES).map { f -> GlyphFrame.of(f.toArray()) }.ifEmpty { listOf(GlyphFrame()) },
        fps = fps.coerceIn(1, 12),
    )
}

enum class VisualizerStyle { BARS, RING }

@Serializable
sealed interface EngineMode {
    @Serializable @SerialName("off") data object Off : EngineMode
    @Serializable @SerialName("art") data class Art(val animation: GlyphAnimation) : EngineMode
    @Serializable @SerialName("text") data class Marquee(val text: String, val stepMs: Long = 90, val brightness: Int = 255) : EngineMode
    @Serializable @SerialName("viz") data class Visualizer(val style: VisualizerStyle = VisualizerStyle.BARS) : EngineMode
    @Serializable @SerialName("pet") data object Pet : EngineMode
    @Serializable @SerialName("tilt") data object TiltGame : EngineMode
    @Serializable @SerialName("pomodoro") data class Pomodoro(val minutes: Int = 25, val startedAt: Long = System.currentTimeMillis()) : EngineMode
}
