package no.agens.testlab.services.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogCaptureTest {
    private val log = """
        1792500000.100  1234  1234 I FA-SVC  : Logging event: origin=app,name=view_item,params=Bundle[{item_id=42, ga_screen=Cart}]
        1792500001.200  1234  1250 E AndroidRuntime: FATAL EXCEPTION: main
        1792500001.200  1234  1250 E AndroidRuntime: Process: com.example.shop, PID: 1234
        1792500001.200  1234  1250 E AndroidRuntime: java.lang.IllegalStateException: Duplicate key 7
        1792500002.300  5555  5555 E AndroidRuntime: FATAL EXCEPTION: main
        1792500002.300  5555  5555 E AndroidRuntime: Process: com.genymobile.scrcpy, PID: 5555
        1792500002.300  5555  5555 E AndroidRuntime: java.lang.RuntimeException: encoder
        1792500003.000  1234  1234 D MyTracker: Tracked analytics event: add_to_cart {sku=9}
    """.trimIndent()

    @Test
    fun crashesAreFoundAndTheRecordersAreTold() {
        val c = LogCapture.crashes(log)
        assertEquals(2, c.size)
        assertEquals("com.example.shop", c[0].process)
        assertTrue("Duplicate key 7" in c[0].first && !c[0].recorder, c[0].toString())
        assertTrue(c[1].recorder, "scrcpy's own crash is the rig's")
    }

    @Test
    fun firebaseEventsAndTheAppsOwnLines() {
        val fa = LogCapture.events(log)
        assertEquals(listOf("view_item"), fa.map { it.name })
        assertTrue("item_id=42" in fa[0].params)
        val own = LogCapture.events(log, Regex("Tracked analytics event: (?<name>\\S+) (?<params>.*)"))
        assertEquals("add_to_cart", own.single().name)
        assertEquals("{sku=9}", own.single().params)
    }
}
