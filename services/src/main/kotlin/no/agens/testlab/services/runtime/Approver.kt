package no.agens.testlab.services.runtime

/**
 * Asks the person at the computer before a write that came in over MCP: Jira comments and labels,
 * uninstalls, CI builds. `testlab serve` (and the background service) install [Approvals.approver],
 * a queue the approvals page, host apps (`/api/v1/approvals`) and `testlab approve` all answer; a
 * stdio session serving itself asks in the client; the default refuses, for embedders and tests.
 */
fun interface Approver {
    /** True to go ahead. [action] is a short title ("Uninstall app"), [detail] the exact payload. */
    suspend fun approve(action: String, detail: String): Boolean

    companion object {
        val HEADLESS = Approver { _, _ -> false }
    }
}

class NotApproved(message: String) : RuntimeException(message)

