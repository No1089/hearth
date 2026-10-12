package dev.homekiosk

/**
 * GB01/GB02/GB03 ("cat printer") wire format. Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Every command is `51 78 <cmd> 00 <len> 00 <data…> <crc8(data)> ff`, written to characteristic
 * ae01. Image rows are 384 px = 48 bytes, LSB = leftmost pixel, 1 = black. Reference
 * implementation (verified on the real printer): tools/catprint.py.
 */
object CatPrinterProtocol {
    const val WIDTH = 384
    const val ROW_BYTES = WIDTH / 8

    const val CMD_FEED = 0xA1
    const val CMD_ROW = 0xA2
    const val CMD_GET_STATE = 0xA3
    const val CMD_QUALITY = 0xA4
    const val CMD_LATTICE = 0xA6
    const val CMD_UPDATE = 0xA9
    const val CMD_ENERGY = 0xAF
    const val CMD_APPLY_ENERGY = 0xBE
    /** Flow-control notification on ae02: byte[2] == 0xAE, byte[6] == 0x10 pause / 0x00 resume. */
    const val NOTIFY_FLOW = 0xAE

    private val CRC = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    /** CRC-8, poly 0x07, init 0. */
    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = CRC[crc xor (b.toInt() and 0xFF)]
        return crc
    }

    fun cmd(code: Int, data: ByteArray): ByteArray {
        require(data.size <= 0xFF) { "payload too long" }
        val out = ByteArray(8 + data.size)
        out[0] = 0x51; out[1] = 0x78; out[2] = code.toByte(); out[3] = 0
        out[4] = data.size.toByte(); out[5] = 0
        data.copyInto(out, 6)
        out[6 + data.size] = crc8(data).toByte()
        out[7 + data.size] = 0xFF.toByte()
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun le16(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF)

    val GET_STATE: ByteArray = cmd(CMD_GET_STATE, bytes(0x00))
    val QUALITY: ByteArray = cmd(CMD_QUALITY, bytes(0x33))
    val APPLY_ENERGY: ByteArray = cmd(CMD_APPLY_ENERGY, bytes(0x01))
    val UPDATE: ByteArray = cmd(CMD_UPDATE, bytes(0x00))
    val LATTICE_ON: ByteArray = cmd(CMD_LATTICE, bytes(0xAA, 0x55, 0x17, 0x38, 0x44, 0x5F, 0x5F, 0x5F, 0x44, 0x38, 0x2C))
    val LATTICE_OFF: ByteArray = cmd(CMD_LATTICE, bytes(0xAA, 0x55, 0x17, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x17))

    fun energy(e: Int) = cmd(CMD_ENERGY, le16(e))
    fun feed(n: Int) = cmd(CMD_FEED, le16(n))

    /** A complete print job: setup, one row command per image row, feed, teardown. */
    fun job(rows: List<ByteArray>, energy: Int = 0xFFFF, feedLines: Int = 90): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 + rows.size * (ROW_BYTES + 8))
        for (c in listOf(GET_STATE, QUALITY, energy(energy), APPLY_ENERGY, UPDATE, LATTICE_ON)) out.write(c)
        for (r in rows) out.write(cmd(CMD_ROW, r))
        for (c in listOf(feed(feedLines), LATTICE_OFF, GET_STATE)) out.write(c)
        return out.toByteArray()
    }

    /** 384 booleans (true = black) -> 48 bytes, LSB = leftmost pixel. */
    fun packRow(black: BooleanArray): ByteArray {
        val out = ByteArray((black.size + 7) / 8)
        for (x in black.indices) if (black[x]) out[x shr 3] = (out[x shr 3].toInt() or (1 shl (x and 7))).toByte()
        return out
    }

    /**
     * Floyd–Steinberg dithers a grayscale image (0 = black … 255 = white, row-major) to 1 bit and
     * packs it into printer rows. [width] must be [WIDTH]; scale before calling.
     */
    fun ditherToRows(gray: IntArray, width: Int, height: Int): List<ByteArray> {
        require(width == WIDTH) { "width must be $WIDTH (got $width)" }
        require(gray.size >= width * height) { "gray array too small" }
        val buf = FloatArray(width * height) { gray[it].coerceIn(0, 255).toFloat() }
        val rows = ArrayList<ByteArray>(height)
        val line = BooleanArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val i = base + x
                val old = buf[i]
                val black = old < 128f
                line[x] = black
                val err = old - (if (black) 0f else 255f)
                if (err == 0f) continue
                if (x + 1 < width) buf[i + 1] += err * 7f / 16f
                if (y + 1 < height) {
                    if (x > 0) buf[i + width - 1] += err * 3f / 16f
                    buf[i + width] += err * 5f / 16f
                    if (x + 1 < width) buf[i + width + 1] += err * 1f / 16f
                }
            }
            rows += packRow(line)
        }
        return rows
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
