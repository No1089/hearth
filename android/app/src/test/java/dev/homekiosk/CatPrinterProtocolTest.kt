package dev.homekiosk

import dev.homekiosk.CatPrinterProtocol.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatPrinterProtocolTest {
    private val w = CatPrinterProtocol.WIDTH

    @Test fun feedVectorMatchesKnownGood() {
        val c = CatPrinterProtocol.cmd(0xA1, byteArrayOf(0x30, 0x00))
        assertEquals("5178a10002003000f9ff", hex(c))
    }

    @Test fun getStateVectorMatchesKnownGood() {
        assertEquals("5178a30001000000ff", hex(CatPrinterProtocol.GET_STATE))
    }

    /** White image with selected pixels black in row 0. */
    private fun image(height: Int, vararg blackInRow0: Int): IntArray {
        val g = IntArray(w * height) { 255 }
        for (x in blackInRow0) g[x] = 0
        return g
    }

    @Test fun pixel0IsLsbOfByte0() {
        val rows = CatPrinterProtocol.ditherToRows(image(1, 0), w, 1)
        assertEquals(1, rows.size)
        assertEquals(CatPrinterProtocol.ROW_BYTES, rows[0].size)
        assertEquals(0x01, rows[0][0].toInt() and 0xFF)
        assertTrue(rows[0].drop(1).all { it.toInt() == 0 })
    }

    @Test fun pixel7IsMsbOfByte0() {
        val rows = CatPrinterProtocol.ditherToRows(image(1, 7), w, 1)
        assertEquals(0x80, rows[0][0].toInt() and 0xFF)
    }

    @Test fun pixel8IsLsbOfByte1() {
        val rows = CatPrinterProtocol.ditherToRows(image(1, 8), w, 1)
        assertEquals(0, rows[0][0].toInt())
        assertEquals(0x01, rows[0][1].toInt() and 0xFF)
    }

    @Test fun midGrayDithersToRoughlyHalfBlack() {
        val h = 64
        val rows = CatPrinterProtocol.ditherToRows(IntArray(w * h) { 128 }, w, h)
        val black = rows.sumOf { r -> r.sumOf { Integer.bitCount(it.toInt() and 0xFF) } }
        val frac = black.toDouble() / (w * h)
        assertTrue("black fraction $frac", frac in 0.4..0.6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongWidth() {
        CatPrinterProtocol.ditherToRows(IntArray(100), 100, 1)
    }

    @Test fun jobStartsAndEndsWithGetState() {
        val rows = CatPrinterProtocol.ditherToRows(image(3, 0, 5), w, 3)
        val job = CatPrinterProtocol.job(rows)
        val gs = CatPrinterProtocol.GET_STATE
        assertArrayEquals(gs, job.copyOfRange(0, gs.size))
        assertArrayEquals(gs, job.copyOfRange(job.size - gs.size, job.size))
        // 3 rows of 48 bytes, each wrapped in an 8-byte command header/trailer
        val rowCmd = CatPrinterProtocol.cmd(0xA2, rows[0])
        assertEquals(56, rowCmd.size)
        assertTrue(hex(job).contains(hex(rowCmd)))
        assertTrue(hex(job).contains(hex(CatPrinterProtocol.energy(0xFFFF))))
        assertEquals("5178af000200ffff", hex(CatPrinterProtocol.energy(0xFFFF)).substring(0, 16))
    }
}
