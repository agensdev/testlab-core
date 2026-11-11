package no.agens.testlab.services.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The window, the service and Claude Desktop's server each hold a SettingsStore on the same file. */
class SharedSettingsTest {
    private val tmp = Files.createTempDirectory("tl-settings").toFile()
    private val file = File(tmp, "settings.json")

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun aChangeInOneProcessReachesTheOthersAndSurvivesTheirWrites(): Unit = runBlocking {
        val window = SettingsStore(file)
        val service = SettingsStore(file)
        window.update { it.copy(proxyman = it.proxyman.copy(mcpServer = "/Users/me/.local/bin/proxyman-mcp")) }
        assertEquals("/Users/me/.local/bin/proxyman-mcp", service.current.proxyman.mcpServer, "read on access")
        // The service then saves something else: it must not put back its old copy without mcpServer.
        service.update { it.copy(mcp = it.mcp.copy(port = 8400)) }
        val fresh = SettingsStore(file).current
        assertEquals("/Users/me/.local/bin/proxyman-mcp", fresh.proxyman.mcpServer)
        assertEquals(8400, fresh.mcp.port)
        // A screen collecting the flow sees the other process's change without asking.
        withTimeout(6_000) { window.settings.first { it.mcp.port == 8400 } }
    }
}
