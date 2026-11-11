package no.agens.testlab.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Claude Desktop's stdio entry: in-process and forwarding to a running app, stdout carrying only protocol. */
class StdioBridgeTest {
    private val tmp = Files.createTempDirectory("tl-stdio").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(mapOf("mcp.token" to "stdio-test-token-123"))).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    private val requests = listOf(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
        """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"lab_read_skill","arguments":{"name":"write-plan"}}}""",
    ).joinToString("\n", postfix = "\n")

    private fun check(stdout: String) {
        val lines = stdout.lines().filter { it.isNotBlank() }
        val byId = lines.map { Json.parseToJsonElement(it).jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(setOf("1", "2", "3"), byId.keys, "every request answered, nothing else on stdout: $lines")
        assertEquals(no.agens.testlab.AppIdentity.SLUG, byId.getValue("1")["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(McpServerTest.EXPECTED_TOOLS.size, byId.getValue("2")["result"]!!.jsonObject["tools"]!!.jsonArray.size)
        assertTrue("# Write a plan" in byId.getValue("3").toString())
    }

    @Test
    fun servesInProcessAndEndsAtEof() {
        StdioBridge.quietStdout()
        val out = ByteArrayOutputStream()
        val t = thread { StdioBridge.serveHere(TestlabMcp(rt), ByteArrayInputStream(requests.encodeToByteArray()), out) }
        t.join(20_000)
        assertTrue(!t.isAlive, "serveHere returns when stdin ends")
        check(out.toString(Charsets.UTF_8))
    }

    @Test
    fun servesInProcessWithALateEof() {
        StdioBridge.quietStdout()
        val out = ByteArrayOutputStream()
        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, 1 shl 16)
        val t = thread { StdioBridge.serveHere(TestlabMcp(rt), pipeIn, out) }
        pipeOut.write(requests.encodeToByteArray()); pipeOut.flush()
        Thread.sleep(2_000)
        pipeOut.close()
        t.join(20_000)
        assertTrue(!t.isAlive, "serveHere returns when stdin ends")
        check(out.toString(Charsets.UTF_8))
    }

    @Test
    fun forwardsToTheRunningApp() {
        val port = ServerSocket(0).use { it.localPort }
        val ep = McpEndpoint(port)
        val gateway = TestlabMcp(rt, ep).gateway().also { it.start(ep) }
        try {
            assertTrue(StdioBridge.appIsListening(ep))
            val out = ByteArrayOutputStream()
            StdioBridge.forward(ep, "stdio-test-token-123", ByteArrayInputStream(requests.encodeToByteArray()), out)
            check(out.toString(Charsets.UTF_8))
        } finally {
            gateway.stop()
        }
        assertTrue(!StdioBridge.appIsListening(ep))
    }
}
