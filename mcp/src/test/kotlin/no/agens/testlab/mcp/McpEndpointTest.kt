package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpEndpointTest {
    @Test
    fun loopbackUrlAndClaudeCommand() {
        val e = McpEndpoint()
        assertEquals("http://127.0.0.1:8378/mcp", e.url)
        val cmd = e.claudeCodeCommand("tok")
        assertTrue(cmd.startsWith("claude mcp add --scope user --transport http ${AppIdentity.SLUG} http://127.0.0.1:8378/mcp"))
        assertTrue(cmd.endsWith("--header \"Authorization: Bearer tok\""))
    }

    @Test
    fun desktopJsonEscapesWindowsPaths() {
        val json = McpEndpoint().claudeDesktopJson("C:\\Program Files\\Testlab\\Testlab.exe")
        assertTrue(json.contains("\"C:\\\\Program Files\\\\Testlab\\\\Testlab.exe\""))
        assertTrue(json.contains("\"--mcp-stdio\""))
    }

    @Test
    fun desktopJsonForAJvmCommand() {
        val json = McpEndpoint().claudeDesktopJson(listOf("/opt/t/runtime/bin/java", "-cp", "/opt/t/lib/*", "Main"))
        assertEquals("""{"mcpServers":{"${AppIdentity.SLUG}":{"command":"/opt/t/runtime/bin/java","args":["-cp","/opt/t/lib/*","Main","--mcp-stdio"]}}}""", json)
    }

    @Test
    fun rejectsPrivilegedPorts() {
        assertFailsWith<IllegalArgumentException> { McpEndpoint(80) }
    }
}
