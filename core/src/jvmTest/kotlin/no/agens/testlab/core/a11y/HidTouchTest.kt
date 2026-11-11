package no.agens.testlab.core.a11y

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HidTouchTest {
    private fun lines(s: String) = s.trim().lines().map { Json.parseToJsonElement(it).jsonObject }
    private fun reports(s: String) = lines(s).filter { it["command"]!!.jsonPrimitive.content == "report" }.map { r -> r["report"]!!.jsonArray.map { it.jsonPrimitive.int } }

    @Test
    fun registersAMultitouchScreenFirstAndWaitsForIt() {
        val l = lines(HidTouch.stream(HidTouch.Taps(540, 1200), 1080, 2400))
        assertEquals("register", l[0]["command"]!!.jsonPrimitive.content)
        val desc = l[0]["descriptor"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertTrue(desc.windowed(2).contains(listOf(0x09, 0x51)), "a contact id: hid-multitouch makes it a direct touchscreen")
        assertTrue(!desc.windowed(2).contains(listOf(0x09, 0x55)), "no contact-count-maximum feature: the driver would wait 5 s for it")
        assertEquals(1000, l[1]["duration"]!!.jsonPrimitive.int)
    }

    @Test
    fun scalesToTheLogicalRangeAndLiftsTheFinger() {
        val r = reports(HidTouch.stream(HidTouch.Taps(1079, 0), 1080, 2400))
        assertEquals(listOf(1, 1, 0, 0xFF, 0x0F, 0, 0, 1), r.first(), "right edge = 4095, top = 0, one contact")
        assertEquals(0, r.last()[1], "tip up at the end")
    }

    @Test
    fun aDoubleTapIsTwoTouchesAndASwipeMovesOneFinger() {
        val dbl = reports(HidTouch.stream(HidTouch.Taps(10, 10, count = 2), 1080, 2400))
        assertEquals(listOf(1, 0, 1, 0), dbl.map { it[1] })
        val sw = reports(HidTouch.stream(HidTouch.Swipe(100, 1200, 900, 1200, 200), 1080, 2400))
        val xs = sw.filter { it[1] == 1 }.map { it[3] or (it[4] shl 8) }
        assertEquals(xs.sorted(), xs, "left to right")
        assertTrue(xs.size >= 5 && sw.last()[1] == 0)
    }
}
