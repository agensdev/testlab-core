package no.agens.testlab.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import kotlinx.coroutines.withTimeoutOrNull
import no.agens.testlab.services.runtime.Approver
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** The MCP client whose tool call is running: a yes or no can be asked through it. */
class McpCaller(val conn: ClientConnection) : AbstractCoroutineContextElement(McpCaller) {
    companion object Key : CoroutineContext.Key<McpCaller>
}

/**
 * Asks the person in their own MCP client (Claude Code shows the question and Accept/Decline;
 * MCP elicitation) when no window or service is there to ask: what a stdio session that serves
 * here uses. A client that can't ask, or a call that came from no client, gets [fallback]'s answer.
 */
object ClientApproval {
    fun approver(fallback: Approver, timeoutSeconds: () -> Int): Approver = Approver { action, detail ->
        val caller = coroutineContext[McpCaller] ?: return@Approver fallback.approve(action, detail)
        val asked = runCatching {
            withTimeoutOrNull(timeoutSeconds().coerceIn(5, 24 * 3600) * 1000L) {
                caller.conn.createElicitation("$action\n\n$detail", ElicitRequestParams.RequestedSchema(properties = emptyMap(), required = emptyList()))
            }
        }
        // The client doesn't do elicitation (or failed): as if nobody could be asked.
        val r = asked.getOrNull() ?: return@Approver if (asked.isFailure) fallback.approve(action, detail) else false
        r.action == ElicitResult.Action.Accept
    }
}
