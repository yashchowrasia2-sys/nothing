package com.zenith.glyphstudio.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.zenith.glyphstudio.data.CELLS
import com.zenith.glyphstudio.data.GRID
import com.zenith.glyphstudio.data.MatrixMask
import com.zenith.glyphstudio.data.PixelFont3x5
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * "Tilt Dodge": tilt the phone left/right to steer the ship (bottom) and dodge falling meteors.
 * Speed ramps with score. On crash: flash, show score, auto-restart.
 */
object TiltDodgeGame {
    private const val PLAYER_Y = 10

    private data class Meteor(var x: Int, var y: Float)

    fun frames(ctx: Context): Flow<IntArray> = callbackFlow {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var tiltX = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { tiltX = tiltX * 0.7f + e.values[0] * 0.3f }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }

        launch {
            var best = 0
            while (isActive) {
                // --- one round ---
                var px = 6f
                val meteors = mutableListOf<Meteor>()
                var score = 0; var tick = 0
                while (isActive) {
                    // device tilted right → values[0] negative → move right
                    px = (px - tiltX * 0.12f).coerceIn(1f, 11f)
                    val speed = 0.18f + score * 0.004f
                    val spawnEvery = (14 - score / 6).coerceAtLeast(4)
                    if (tick % spawnEvery == 0) meteors += Meteor(Random.nextInt(1, 12), 0f)
                    meteors.forEach { it.y += speed }
                    val passed = meteors.count { it.y >= GRID }
                    score += passed
                    meteors.removeAll { it.y >= GRID }

                    val ix = px.toInt()
                    val hit = meteors.any { it.y.toInt() in PLAYER_Y..PLAYER_Y + 1 && it.x in ix - 1..ix + 1 && (it.x == ix || it.y.toInt() == PLAYER_Y + 1) }

                    val f = IntArray(CELLS)
                    meteors.forEach { m ->
                        val y = m.y.toInt()
                        if (y in 0 until GRID) f[y * GRID + m.x] = 255
                        if (y - 1 in 0 until GRID) f[(y - 1) * GRID + m.x] = 70 // trail
                    }
                    // ship: ^ shape
                    f[PLAYER_Y * GRID + ix] = 255
                    f[(PLAYER_Y + 1) * GRID + ix - 1] = 200; f[(PLAYER_Y + 1) * GRID + ix] = 255; f[(PLAYER_Y + 1) * GRID + ix + 1] = 200
                    trySend(MatrixMask.apply(f))

                    if (hit) break
                    tick++; delay(40)
                }
                best = maxOf(best, score)
                // crash: 3 flashes, then score
                repeat(3) {
                    trySend(MatrixMask.apply(IntArray(CELLS) { 255 })); delay(90)
                    trySend(IntArray(CELLS)); delay(90)
                }
                val s = IntArray(CELLS); PixelFont3x5.drawNumber(s, score, 255)
                MatrixMask.ring.forEachIndexed { i, led -> if (i < MatrixMask.ring.size * minOf(score, best) / maxOf(best, 1)) s[led] = 60 }
                trySend(MatrixMask.apply(s)); delay(2_000)
            }
        }
        awaitClose { sm?.unregisterListener(listener) }
    }
}
