package no.agens.testlab.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlowCallsTest {
    @Test
    fun `a slow run answers pending, and the next call joins it instead of starting again`() = runBlocking {
        val slow = SlowCalls(budgetMs = 100)
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val work: suspend () -> String = { started.incrementAndGet(); gate.await(); "installed" }
        assertEquals("pending", slow.run("k", "pending", work))
        assertEquals("pending", slow.run("k", "pending", work))
        gate.complete(Unit)
        assertEquals("installed", slow.run("k", "pending", work))
        assertEquals(1, started.get())
        assertEquals("installed", slow.run("k", "pending", work), "a collected result is not handed out again: a new call runs anew")
        assertEquals(2, started.get())
    }

    @Test
    fun `a failure reaches the caller and is not kept`() = runBlocking {
        val slow = SlowCalls(budgetMs = 1_000)
        assertFailsWith<IllegalStateException> { slow.run("k", "pending") { error("no APK") } }
        assertEquals("ok", slow.run("k", "pending") { "ok" })
    }
}
