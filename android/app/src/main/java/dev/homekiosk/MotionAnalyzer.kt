package dev.homekiosk

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX analyzer: throttles to ~[fps] frames per second, copies the Y plane and hands it to
 * [MotionDetector]. [onFrame] is called on the analysis thread with the changed-block fraction.
 */
class MotionAnalyzer(
    private val detector: MotionDetector,
    private val fps: Double = 2.0,
    private val onFrame: (fraction: Float) -> Unit,
) : ImageAnalysis.Analyzer {

    private val minIntervalMs = (1000.0 / fps).toLong()
    private var lastMs = 0L
    private var buf = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMs < minIntervalMs) return
            lastMs = now

            val plane = image.planes[0]
            val bb = plane.buffer
            bb.rewind()
            val len = bb.remaining()
            if (buf.size != len) buf = ByteArray(len)
            bb.get(buf, 0, len)
            if (image.width < detector.gridW || image.height < detector.gridH) return
            val f = detector.process(buf, image.width, image.height, plane.rowStride, plane.pixelStride)
            onFrame(f)
        } catch (_: Throwable) {
            // Never let a bad frame kill the analysis thread.
        } finally {
            image.close()
        }
    }
}
