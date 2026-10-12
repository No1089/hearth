package dev.homekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    private val w = 320
    private val h = 240

    private fun frame(value: Int) = ByteArray(w * h) { value.toByte() }

    /** Paints a rectangle of [value] into [f]. */
    private fun rect(f: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        for (y in y0 until y1) for (x in x0 until x1) f[y * w + x] = value.toByte()
    }

    @Test fun firstFrameReportsNoMotion() {
        val d = MotionDetector()
        assertEquals(0f, d.process(frame(100), w, h), 0f)
    }

    @Test fun identicalFramesReportNoMotion() {
        val d = MotionDetector()
        d.process(frame(100), w, h)
        assertEquals(0f, d.process(frame(100), w, h), 0f)
    }

    @Test fun smallNoiseBelowThresholdIgnored() {
        val d = MotionDetector(blockDelta = 16)
        d.process(frame(100), w, h)
        assertEquals(0f, d.process(frame(110), w, h), 0f)
    }

    @Test fun globalChangeAboveThresholdIsFullMotion() {
        val d = MotionDetector(blockDelta = 16)
        d.process(frame(50), w, h)
        assertEquals(1f, d.process(frame(200), w, h), 0f)
    }

    @Test fun localObjectChangesProportionalFraction() {
        val d = MotionDetector(gridW = 32, gridH = 24, blockDelta = 16)
        d.process(frame(60), w, h)
        val f = frame(60)
        // 320/32 = 10px blocks, 240/24 = 10px blocks: an 80x60 box covers exactly 8x6 = 48 blocks.
        rect(f, 100, 100, 180, 160, 220)
        val frac = d.process(f, w, h)
        assertEquals(48f / (32 * 24), frac, 1e-6f)
    }

    @Test fun downsampleAveragesBlocksAndHonoursRowStride() {
        val d = MotionDetector(gridW = 2, gridH = 2)
        val stride = 6 // 4 px wide + 2 px padding per row
        val buf = ByteArray(stride * 4) { 0 }
        fun set(x: Int, y: Int, v: Int) { buf[y * stride + x] = v.toByte() }
        // top-left block (2x2) = 10,20,30,40 -> avg 25; padding bytes are 255 and must be ignored
        set(0, 0, 10); set(1, 0, 20); set(0, 1, 30); set(1, 1, 40)
        for (y in 0 until 4) { set(4, y, 255); set(5, y, 255) }
        // bottom-right block = 200
        set(2, 2, 200); set(3, 2, 200); set(2, 3, 200); set(3, 3, 200)
        val g = d.downsample(buf, 4, 4, rowStride = stride)
        assertEquals(listOf(25, 0, 0, 200), g.toList())
    }

    @Test fun unsignedLumaHandled() {
        val d = MotionDetector(gridW = 1, gridH = 1)
        assertEquals(250, d.downsample(ByteArray(4) { 250.toByte() }, 2, 2)[0])
    }

    @Test fun resolutionChangeResetsBaseline() {
        val d = MotionDetector()
        d.process(frame(10), w, h)
        assertEquals(0f, d.process(ByteArray(640 * 480) { 200.toByte() }, 640, 480), 0f)
    }

    @Test fun resetDropsBaseline() {
        val d = MotionDetector()
        d.process(frame(10), w, h)
        d.reset()
        assertEquals(0f, d.process(frame(200), w, h), 0f)
        assertTrue(d.process(frame(10), w, h) > 0.99f)
    }
}
