package no.agens.testlab.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Work that may outlast the client's wait for a tool result (claude.ai and Claude Desktop give a call
 * on the user's computer 60 s). The work runs on its own; a call waits at most [budgetMs] and then
 * answers [pending]. A later call with the same key joins the same run instead of starting it again,
 * and gets its result. A finished run is kept for [keepMs] for the caller that comes back for it.
 */
internal class SlowCalls(private val budgetMs: Long = 45_000, private val keepMs: Long = 600_000) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = ConcurrentHashMap<String, Deferred<String>>()

    suspend fun run(key: String, pending: String, work: suspend () -> String): String {
        // The caller's profile scope goes along: the work outlives the call, not its platform.
        val caller = kotlin.coroutines.coroutineContext.minusKey(kotlinx.coroutines.Job)
        val run = runs.computeIfAbsent(key) { k ->
            scope.async(caller) { work() }.also { d -> d.invokeOnCompletion { scope.launch { delay(keepMs); runs.remove(k, d) } } }
        }
        val done = withTimeoutOrNull(budgetMs) { runCatching { run.await() } } ?: return pending
        runs.remove(key, run)
        return done.getOrThrow()
    }
}
