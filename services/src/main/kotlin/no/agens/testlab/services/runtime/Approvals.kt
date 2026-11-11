package no.agens.testlab.services.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.time.Instant

/**
 * The writes waiting for a yes or no. Anyone who can answer sees the same queue: the approvals
 * page (`testlab open approvals`), a host app over the control API (`/api/v1/approvals`), or
 * `testlab approve`. The first answer wins; nobody answering within the timeout is a no.
 */
class Approvals(private val timeoutSeconds: () -> Int = { DEFAULT_TIMEOUT_S }) {
    class Pending(
        val id: String,
        val action: String,
        val detail: String,
        val created: Instant,
        val expires: Instant,
        internal val answer: CompletableDeferred<Answer>,
    )

    data class Answer(val approved: Boolean, val by: String)

    private val queue = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = queue.asStateFlow()

    /** Answered or timed-out approvals, newest first, for `status` and the event stream. */
    data class Resolved(val id: String, val action: String, val approved: Boolean, val by: String, val at: Instant)
    private val resolvedFlow = MutableStateFlow<List<Resolved>>(emptyList())
    val resolved: StateFlow<List<Resolved>> = resolvedFlow.asStateFlow()

    /** Waits for an answer; false on no, on timeout and when the caller is cancelled. */
    suspend fun ask(action: String, detail: String): Boolean {
        val now = Instant.now()
        // A preview of the person's own button waits as long as they read it.
        val secs = timeoutSeconds().coerceIn(5, 24 * 3600)
        val p = Pending(newId(), action, detail, now, now.plusSeconds(secs.toLong()), CompletableDeferred())
        queue.update { it + p }
        var outcome = Answer(false, "timeout")
        try {
            outcome = withTimeoutOrNull(secs * 1000L) { p.answer.await() } ?: outcome
            return outcome.approved
        } finally {
            queue.update { l -> l.filterNot { it.id == p.id } }
            resolvedFlow.update { (listOf(Resolved(p.id, p.action, outcome.approved, outcome.by, Instant.now())) + it).take(50) }
        }
    }

    /**
     * Answers [id] for [by] ("api:page", "api", "cli"…). False when there is no such approval (already
     * answered or timed out).
     */
    fun answer(id: String, approved: Boolean, by: String): Boolean {
        val p = queue.value.firstOrNull { it.id == id } ?: return false
        return p.answer.complete(Answer(approved, by))
    }

    fun get(id: String): Pending? = queue.value.firstOrNull { it.id == id }

    /** An [Approver] that queues: what `testlab serve` (and the background service) install. */
    fun approver(): Approver = Approver { action, detail ->
        ask(action, detail)
    }

    companion object {
        const val DEFAULT_TIMEOUT_S = 120
        private val random = SecureRandom()
        private fun newId(): String = "ap_" + (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[random.nextInt(31)] }.joinToString("")
    }
}
