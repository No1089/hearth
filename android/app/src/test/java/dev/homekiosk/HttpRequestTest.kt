package dev.homekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRequestTest {
    private fun parse(raw: String) = HttpRequest.parse(raw.byteInputStream())

    @Test fun parsesMethodPathQueryHeaders() {
        val r = parse("POST /url?u=https%3A%2F%2Fdash.example.com%2F%3Fa%3D1&token=abc HTTP/1.1\r\nHost: 192.0.2.10\r\nContent-Length: 0\r\n\r\n")!!
        assertEquals("POST", r.method)
        assertEquals("/url", r.path)
        assertEquals("https://dash.example.com/?a=1", r.query["u"])
        assertEquals("192.0.2.10", r.headers["host"])
    }

    @Test fun tokenFromQueryOrBearer() {
        val q = parse("GET /status?token=s3cret HTTP/1.1\r\n\r\n")!!
        assertTrue(q.isAuthorized("s3cret"))
        val b = parse("GET /status HTTP/1.1\r\nAuthorization: Bearer s3cret\r\n\r\n")!!
        assertTrue(b.isAuthorized("s3cret"))
        assertFalse(b.isAuthorized("other"))
    }

    @Test fun rejectsWhenNoTokenConfiguredOrPresented() {
        val r = parse("GET /status?token= HTTP/1.1\r\n\r\n")!!
        assertFalse(r.isAuthorized(""))
        val none = parse("GET /status HTTP/1.1\r\n\r\n")!!
        assertFalse(none.isAuthorized("s3cret"))
    }

    @Test fun drainsBody() {
        val r = parse("POST /wake HTTP/1.1\r\nContent-Length: 4\r\n\r\n{}{}")!!
        assertEquals("/wake", r.path)
    }

    @Test fun garbageIsNull() {
        assertNull(parse(""))
        assertNull(parse("NONSENSE\r\n\r\n"))
    }

    @Test fun nightWindowWrapsMidnight() {
        val s = Config.parseHm("22:00")!!
        val e = Config.parseHm("06:30")!!
        assertTrue(TimeWindow.contains(s, e, 23 * 60))
        assertTrue(TimeWindow.contains(s, e, 3 * 60))
        assertFalse(TimeWindow.contains(s, e, 6 * 60 + 30))
        assertFalse(TimeWindow.contains(s, e, 12 * 60))
        assertTrue(TimeWindow.contains(60, 120, 90))
        assertNull(Config.parseHm("25:00"))
    }
}
