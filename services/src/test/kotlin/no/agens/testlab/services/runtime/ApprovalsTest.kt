package no.agens.testlab.services.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalsTest {
    private suspend fun Approvals.first(): Approvals.Pending = withTimeout(5_000) {
        while (pending.value.isEmpty()) delay(5)
        pending.value.first()
    }

    @Test
    fun theFirstAnswerWins(): Unit = runBlocking {
        val a = Approvals()
        val write = async { a.ask("Uninstall app", "com.example.app") }
        val p = a.first()
        assertTrue(p.id.startsWith("ap_"))
        assertTrue(a.answer(p.id, true, "cli"))
        assertFalse(a.answer(p.id, false, "api"), "already answered")
        assertTrue(write.await())
        assertEquals(emptyList(), a.pending.value)
        assertEquals(Approvals.Resolved(p.id, "Uninstall app", true, "cli", a.resolved.value.first().at), a.resolved.value.first())
    }

    @Test
    fun noAnswerInTimeIsANo(): Unit = runBlocking {
        val a = Approvals { 5 }
        val started = System.nanoTime()
        val write = async { a.ask("Comment", "text") }
        a.first()
        assertFalse(write.await())
        assertTrue((System.nanoTime() - started) / 1_000_000 in 4_500..9_000)
        assertEquals("timeout", a.resolved.value.first().by)
        assertFalse(a.answer("ap_unknown", true, "cli"))
    }
}
