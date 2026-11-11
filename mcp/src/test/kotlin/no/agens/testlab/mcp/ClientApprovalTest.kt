package no.agens.testlab.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stdio session with no window to ask: a write that needs a yes is asked in the client (MCP elicitation). */
class ClientApprovalTest {
    private val tmp = Files.createTempDirectory("tl-elicit").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore()).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    /** Runs one tool call that asks for a yes; the client answers elicitations with [answer] (null: it can't elicit). */
    private fun session(answer: String?): Pair<String, List<JsonObject>> {
        rt.approver = ClientApproval.approver(rt.approver) { 30 }
        val mcp = TestlabMcp(rt)
        mcp.tools.tool("test_needs_yes", "asks for a yes") { _ -> ToolBox.text("approved=" + rt.approver.approve("Start a build", "Run build.yml on main?")) }
        val toServer = PipedOutputStream(); val serverIn = PipedInputStream(toServer, 1 shl 16)
        val serverOut = PipedOutputStream(); val fromServer = PipedInputStream(serverOut, 1 shl 16)
        val t = thread { StdioBridge.serveHere(mcp, serverIn, serverOut) }
        fun send(s: String) { toServer.write((s + "\n").toByteArray()); toServer.flush() }
        val caps = if (answer != null) """{"elicitation":{}}""" else "{}"
        send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":$caps,"clientInfo":{"name":"t","version":"1"}}}""")
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"test_needs_yes","arguments":{}}}""")
        val seen = mutableListOf<JsonObject>()
        val reader = fromServer.bufferedReader()
        var result = ""
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val o = Json.parseToJsonElement(line).jsonObject
            seen += o
            if (o["method"]?.jsonPrimitive?.content == "elicitation/create") {
                send("""{"jsonrpc":"2.0","id":${o["id"]},"result":{"action":"$answer"}}""")
            } else if (o["id"]?.jsonPrimitive?.content == "2") {
                result = o.toString(); break
            }
        }
        toServer.close(); t.join(5_000)
        return result to seen
    }

    @Test
    fun aYesInTheClientApproves() {
        val (result, seen) = session("accept")
        val ask = seen.single { it["method"]?.jsonPrimitive?.content == "elicitation/create" }
        assertTrue("Run build.yml on main?" in ask["params"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertTrue("approved=true" in result, result)
    }

    @Test
    fun aDeclineIsANo() {
        assertTrue("approved=false" in session("decline").first)
    }

    @Test
    fun aClientThatCantAskGetsTheOldNo() {
        val (result, seen) = session(null)
        assertEquals(0, seen.count { it["method"]?.jsonPrimitive?.content == "elicitation/create" })
        assertTrue("approved=false" in result, result)
    }
}
