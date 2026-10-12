package dev.homekiosk

import kotlin.math.abs

/**
 * Pure frame-differencing motion detector (no Android dependencies, unit-tested on the JVM).
 *
 * Each luma (Y) frame is reduced to a coarse grid of averaged blocks; a block "changed" when its
 * average luma moved by more than [blockDelta]. The result of [process] is the fraction (0..1) of
 * blocks that changed since the previous frame. Callers compare it with their sensitivity setting.
 */
class MotionDetector(
    val gridW: Int = 32,
    val gridH: Int = 24,
    @Volatile var blockDelta: Int = 16,
) {
    private var prev: IntArray? = null
    private var prevW = 0
    private var prevH = 0

    /**
     * Averages the Y plane into a [gridW] x [gridH] grid.
     * [rowStride]/[pixelStride] follow android.media.Image.Plane semantics.
     */
    fun downsample(y: ByteArray, width: Int, height: Int, rowStride: Int = width, pixelStride: Int = 1): IntArray {
        require(width >= gridW && height >= gridH) { "frame ${width}x$height smaller than grid" }
        val grid = IntArray(gridW * gridH)
        for (gy in 0 until gridH) {
            val y0 = gy * height / gridH
            val y1 = (gy + 1) * height / gridH
            for (gx in 0 until gridW) {
                val x0 = gx * width / gridW
                val x1 = (gx + 1) * width / gridW
                var sum = 0L
                var n = 0
                for (py in y0 until y1) {
                    val row = py * rowStride
                    for (px in x0 until x1) {
                        val idx = row + px * pixelStride
                        if (idx < y.size) {
                            sum += (y[idx].toInt() and 0xFF)
                            n++
                        }
                    }
                }
                grid[gy * gridW + gx] = if (n == 0) 0 else (sum / n).toInt()
            }
        }
        return grid
    }

    /** Fraction (0..1) of blocks whose value differs by more than [blockDelta]. */
    fun compare(a: IntArray, b: IntArray): Float {
        require(a.size == b.size) { "grid size mismatch" }
        if (a.isEmpty()) return 0f
        var changed = 0
        val t = blockDelta
        for (i in a.indices) if (abs(a[i] - b[i]) > t) changed++
        return changed.toFloat() / a.size
    }

    /**
     * Feeds one frame; returns the changed fraction relative to the previous frame
     * (0 for the first frame or after a resolution change).
     */
    fun process(y: ByteArray, width: Int, height: Int, rowStride: Int = width, pixelStride: Int = 1): Float {
        val cur = downsample(y, width, height, rowStride, pixelStride)
        val p = prev
        val result = if (p == null || prevW != width || prevH != height) 0f else compare(p, cur)
        prev = cur
        prevW = width
        prevH = height
        return result
    }

    fun reset() {
        prev = null
    }
}
