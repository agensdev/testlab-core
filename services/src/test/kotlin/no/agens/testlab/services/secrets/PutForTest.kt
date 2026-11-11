package no.agens.testlab.services.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PutForTest {
    @Test
    fun `a new plain token also replaces the site's own, which is the one used`() {
        val s = MemorySecretStore(mapOf("jira.token@example.atlassian.net" to "old"))
        assertEquals("jira.token@example.atlassian.net", SecretStore.putFor(s, "jira.token", "example.atlassian.net", "new"))
        assertEquals("new", SecretStore.scoped(s, "jira.token", "example.atlassian.net"))
        assertEquals("new", s.get("jira.token"))
    }

    @Test
    fun `without a site's own token only the plain one is saved`() {
        val s = MemorySecretStore()
        assertNull(SecretStore.putFor(s, "jira.token", "example.atlassian.net", "new"))
        assertEquals(setOf("jira.token"), s.keys())
    }
}
