package com.zenith.glyphstudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.zenith.glyphstudio.data.GRID
import com.zenith.glyphstudio.data.MatrixMask

/** Circular 13x13 LED grid. Pass [onPaint] to make it a touch-to-draw canvas; null → read-only preview. */
@Composable
fun MatrixCanvas(
    pixels: IntArray,
    modifier: Modifier = Modifier,
    onion: IntArray? = null,
    onPaint: ((Int) -> Unit)? = null,
) {
    val paint by rememberUpdatedState(onPaint)
    var m = modifier.aspectRatio(1f)
    if (onPaint != null) {
        m = m.pointerInput(Unit) {
            fun hit(o: Offset) {
                val cell = size.width / GRID.toFloat()
                val x = (o.x / cell).toInt(); val y = (o.y / cell).toInt()
                if (x in 0 until GRID && y in 0 until GRID) paint?.invoke(y * GRID + x)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                hit(down.position); down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    hit(change.position); change.consume()
                }
            }
        }
    }
    Canvas(m) {
        val cell = size.width / GRID
        val r = cell * 0.40f
        for (i in pixels.indices) {
            val x = i % GRID; val y = i / GRID
            val c = Offset(x * cell + cell / 2, y * cell + cell / 2)
            if (!MatrixMask.mask[i]) continue
            val v = pixels[i]
            drawCircle(Color(0xFF1C1C1C), r, c)
            if (v > 0) drawCircle(Color.White.copy(alpha = 0.15f + 0.85f * v / 255f), r, c)
            else if (onion != null && onion[i] > 0) drawCircle(Color(0xFFD71921).copy(alpha = 0.28f), r * 0.75f, c)
        }
    }
}
