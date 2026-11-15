package com.aimcoach.app

import java.nio.ByteBuffer
import kotlin.math.min

/**
 * All detector thresholds live here so you can calibrate them in one place.
 * Fractions are "share of sampled pixels inside the crosshair box".
 */
object DetectorConfig {
    /** Side of the square box around screen centre, as a fraction of the shorter screen side. */
    @Volatile var boxFraction = 0.14f

    /** Look at every Nth pixel inside the box (higher = faster, less precise). */
    @Volatile var sampleStep = 2

    /** A pixel is "red" when R >= redMin and G, B <= redMaxOther. */
    @Volatile var redMin = 190
    @Volatile var redMaxOther = 90

    /** A pixel is "white" when R, G, B >= whiteMin and (max - min) <= whiteSpread. */
    @Volatile var whiteMin = 220
    @Volatile var whiteSpread = 35

    /** How far above the rolling baseline the red / white fraction must jump to count as a hit marker. */
    @Volatile var redSpike = 0.015f
    @Volatile var whiteSpike = 0.020f

    /** Rolling baseline speed (0..1). Smaller = slower baseline. */
    @Volatile var baselineAlpha = 0.05f

    /** Minimum time between two counted hits. */
    @Volatile var cooldownMs = 280L

    /** Frames used only to learn the baseline after the detector is reset. */
    @Volatile var warmupFrames = 10
}

/** Looks for hit markers (red = headshot, white = body) in a small box around the crosshair. */
class HitDetector {

    enum class Hit { HEAD, BODY }

    private var redBase = 0f
    private var whiteBase = 0f
    private var frames = 0
    private var lastHitAt = -10_000L
    private var redArmed = true
    private var whiteArmed = true

    fun reset() {
        redBase = 0f
        whiteBase = 0f
        frames = 0
        lastHitAt = -10_000L
        redArmed = true
        whiteArmed = true
    }

    /**
     * @param buf RGBA_8888 pixels (bytes R, G, B, A) of one frame.
     * @return the detected hit, or null.
     */
    fun process(
        buf: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int, nowMs: Long
    ): Hit? {
        val c = DetectorConfig
        val shorter = min(width, height)
        val box = (shorter * c.boxFraction).toInt().coerceIn(8, shorter)
        val x0 = (width - box) / 2
        val y0 = (height - box) / 2
        val step = c.sampleStep.coerceAtLeast(1)

        var red = 0
        var white = 0
        var n = 0
        val limit = buf.limit()
        var y = y0
        while (y < y0 + box) {
            var x = x0
            while (x < x0 + box) {
                val off = y * rowStride + x * pixelStride
                if (off + 2 < limit) {
                    val r = buf.get(off).toInt() and 0xFF
                    val g = buf.get(off + 1).toInt() and 0xFF
                    val b = buf.get(off + 2).toInt() and 0xFF
                    if (r >= c.redMin && g <= c.redMaxOther && b <= c.redMaxOther) {
                        red++
                    } else if (r >= c.whiteMin && g >= c.whiteMin && b >= c.whiteMin) {
                        val mx = maxOf(r, g, b)
                        val mn = minOf(r, g, b)
                        if (mx - mn <= c.whiteSpread) white++
                    }
                    n++
                }
                x += step
            }
            y += step
        }
        if (n == 0) return null

        val redFrac = red.toFloat() / n
        val whiteFrac = white.toFloat() / n
        frames++
        if (frames == 1) {
            redBase = redFrac
            whiteBase = whiteFrac
        }

        val redOver = redFrac - redBase
        val whiteOver = whiteFrac - whiteBase

        // Baseline only follows non-spike frames so a long marker is not absorbed into it.
        if (redOver < c.redSpike) redBase += c.baselineAlpha * (redFrac - redBase)
        if (whiteOver < c.whiteSpike) whiteBase += c.baselineAlpha * (whiteFrac - whiteBase)

        // Re-arm once the signal has fallen back, so one long marker counts once.
        if (redOver < c.redSpike * 0.5f) redArmed = true
        if (whiteOver < c.whiteSpike * 0.5f) whiteArmed = true

        if (frames <= c.warmupFrames) return null

        val ready = nowMs - lastHitAt >= c.cooldownMs
        if (redOver >= c.redSpike) {
            if (redArmed && ready) {
                redArmed = false
                whiteArmed = false
                lastHitAt = nowMs
                return Hit.HEAD
            }
            return null // red marker present: never count it as a body hit
        }
        if (whiteOver >= c.whiteSpike && whiteArmed && ready) {
            whiteArmed = false
            lastHitAt = nowMs
            return Hit.BODY
        }
        return null
    }
}
