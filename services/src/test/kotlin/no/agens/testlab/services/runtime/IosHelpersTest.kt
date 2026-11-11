package no.agens.testlab.services.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.agens.testlab.device.ios.XcrunCli
import no.agens.testlab.services.proxyman.McpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Simulators made from scratch, and the client for Xcode's MCP server, against fakes of xcrun and the server. */
class IosHelpersTest {
    private val runtimesJson = """{"runtimes":[
        {"identifier":"com.apple.CoreSimulator.SimRuntime.iOS-26-5","name":"iOS 26.5","version":"26.5","isAvailable":true},
        {"identifier":"com.apple.CoreSimulator.SimRuntime.iOS-27-0","name":"iOS 27.0","version":"27.0","isAvailable":true},
        {"identifier":"com.apple.CoreSimulator.SimRuntime.watchOS-12-0","name":"watchOS 12.0","version":"12.0","isAvailable":true}]}"""
    private val typesJson = """{"devicetypes":[
        {"identifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-16","name":"iPhone 16","productFamily":"iPhone"},
        {"identifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-18","name":"iPhone 18","productFamily":"iPhone"},
        {"identifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-17","name":"iPhone 17","productFamily":"iPhone"},
        {"identifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro","name":"iPhone 17 Pro","productFamily":"iPhone"},
        {"identifier":"com.apple.CoreSimulator.SimDeviceType.iPad-Air","name":"iPad Air","productFamily":"iPad"}]}"""

    @Test
    fun runtimesAndModelsInTheOrderToTry() {
        assertEquals(listOf("iOS 27.0", "iOS 26.5"), XcrunCli.parseRuntimes(runtimesJson).map { it.name }, "iOS only, newest first")
        assertEquals(listOf("iPhone 18", "iPhone 17", "iPhone 16", "iPhone 17 Pro"), XcrunCli.phoneCandidates(XcrunCli.parseDeviceTypes(typesJson)).map { it.name })
    }

    @Test
    fun aSimulatorFromNothing() {
        val calls = mutableListOf<String>()
        var downloaded = false
        var made: String? = null
        val x = XcrunCli(exec = { args, _ ->
            val cmd = args.joinToString(" ")
            calls += cmd
            when {
                cmd == "simctl list runtimes -j" -> XcrunCli.Result(0, if (downloaded) runtimesJson else """{"runtimes":[]}""", "")
                cmd == "xcodebuild -downloadPlatform iOS" -> { downloaded = true; XcrunCli.Result(0, "Done", "") }
                cmd == "simctl list devicetypes -j" -> XcrunCli.Result(0, typesJson, "")
                // The newest model is too new for the runtime; the next one works.
                cmd.startsWith("simctl create") && "iPhone-18" in cmd -> XcrunCli.Result(1, "", "Incompatible device")
                cmd.startsWith("simctl create") -> { made = "UDID-17"; XcrunCli.Result(0, "UDID-17\n", "") }
                cmd == "simctl list devices available -j" -> XcrunCli.Result(0, made?.let { """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-27-0":[{"udid":"$it","name":"iPhone 17 (Testlab)","state":"Shutdown","isAvailable":true}]}}""" } ?: """{"devices":{}}""", "")
                else -> XcrunCli.Result(0, "", "")
            }
        })
        val notes = mutableListOf<String>()
        val s = Simulators { x }.create { notes += it }
        assertEquals("UDID-17" to "iOS 27.0", s.udid to s.runtime)
        assertTrue(notes.single().startsWith("downloading the iOS simulator runtime"))
        assertTrue(calls.any { it == "simctl create iPhone 17 (Testlab) com.apple.CoreSimulator.SimDeviceType.iPhone-17 com.apple.CoreSimulator.SimRuntime.iOS-27-0" }, calls.toString())
        assertFailsWith<IllegalArgumentException> { Simulators { x }.create("iPhone 3G") }
        assertFailsWith<NotOnPlatform> { Simulators { null }.list() }
    }

    /** A stand-in for `xcrun mcpbridge`: answers tools/call with structured content. */
    private class FakeXcode(val answer: (String, JsonObject) -> JsonObject) : McpClient {
        var starts = 0
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override var alive = false
        override suspend fun start() { starts++; alive = true }
        override suspend fun listTools() = emptyList<JsonObject>()
        override suspend fun callTool(name: String, args: JsonObject): JsonObject { calls += name to args; return answer(name, args) }
        override fun close() { alive = false }
    }

    @Test
    fun xcodesStructuredAnswerAndItsErrors() = runBlocking {
        val log = File(Files.createTempDirectory("tl-xcode").toFile(), "x.log")
        val fake = FakeXcode { name, _ ->
            if (name == "GetCrashIssueLogs") buildJsonObject { put("isError", true); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "No signature named x") }) } }
            else buildJsonObject {
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "{}") }) }
                putJsonObject("structuredContent") { put("success", true); put("bundleId", "com.example.shop"); put("data", """[{"signatureName":"SIGABRT in Cart"}]""") }
            }
        }
        val x = XcodeMcp(log, connect = { fake }, exec = { _, _ -> -1 to "" })
        val r = x.call("GetTopCrashIssues", JsonObject(mapOf("bundle_id" to JsonPrimitive("com.example.shop"))))
        assertEquals("com.example.shop", (r["bundleId"] as JsonPrimitive).content)
        x.call("GetTopCrashIssues", JsonObject(emptyMap()))
        assertEquals(1, fake.starts, "one bridge for every call")
        val e = assertFailsWith<IllegalStateException> { x.call("GetCrashIssueLogs", JsonObject(emptyMap())) }
        assertTrue("No signature named x" in e.message!!, e.message)
    }

    @Test
    fun theRequestsWaitingForApproval() {
        val status = """{"enabled":true,"pendingRequests":[{"id":"A1B2","kind":"agent","path":"/x/java"},{"id":"C3D4","kind":"folder"}],"agentPermissions":[{"id":"OLD"}]}"""
        assertEquals(listOf("A1B2", "C3D4"), XcodeMcp.pendingIds(status))
        assertEquals(emptyList(), XcodeMcp.pendingIds("not json"))
    }
}
