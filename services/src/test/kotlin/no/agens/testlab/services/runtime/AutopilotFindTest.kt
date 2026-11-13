package no.agens.testlab.services.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.core.autopilot.HttpPost
import no.agens.testlab.core.autopilot.HttpReply
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutopilotFindTest {
    private fun jpeg(w: Int, h: Int) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    private class Fake(val status: Int = 200, val answer: String) : HttpPost {
        var url = ""; var headers = emptyMap<String, String>(); var body = ""
        override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long, connectTimeoutMs: Long?): HttpReply {
            this.url = url; this.headers = headers; this.body = body; return HttpReply(status, answer)
        }
    }

    private val FOUND = """{"answers":{"target":{"type":"box","box":{"top":0.1,"bottom":0.2,"left":0.8,"right":0.9},"confidence":0.91},"present":{"type":"noul","noul":0.97}},"usage":{"input_tokens":300}}"""

    @Test
    fun theRequestAndTheTapPoint() = runBlocking {
        val http = Fake(answer = FOUND)
        val r = AutopilotFind(http, { "k-secret" }).find("the back arrow", jpeg(1440, 3120), 1440, 3120)
        assertEquals(mapOf("Content-Type" to "application/json", "Authorization" to "Bearer k-secret", "TypeSafe-Danger-Zone-Images" to "true"), http.headers)
        val body = Json.parseToJsonElement(http.body) as JsonObject
        val s1 = ((body["assets"] as JsonObject)["s1"] as JsonObject)
        assertEquals("jev-latest", (body["model"] as JsonPrimitive).content)
        assertTrue((s1["data_base64"] as JsonPrimitive).content.let { !it.startsWith("data:") && it.length > 100 })
        assertTrue("\"instructions\":\"the back arrow in `screen`\"" in http.body && "\"instructions\":\"`screen` shows the back arrow\"" in http.body && "\"type\":\"noul\"" in http.body, http.body)
        // The long side is scaled to 1400 (TypeSafe rejects > 1568), and the centre is in tap pixels.
        assertEquals(646 to 1400, r.image)
        assertTrue(r.found && r.confidence == 0.91)
        assertEquals(1224 to 468, r.x to r.y)
        assertFalse("k-secret" in r.json().toString())
    }

    @Test
    fun notThereLandscapeAndErrors() = runBlocking {
        val absent = FOUND.replace("0.97", "0.12")
        val r = AutopilotFind(Fake(answer = absent), { "k" }).find("x", jpeg(2400, 1080), 1080, 2400)
        assertFalse(r.found, "Box always draws a box: the noul decides")
        // A landscape picture with a portrait size: the size is turned to match.
        assertEquals(2040 to 162, r.x to r.y)
        val e = assertFailsWith<IllegalStateException> { AutopilotFind(Fake(400, """{"error":"image too small"}"""), { "k" }).find("x", jpeg(600, 1200), 600, 1200) }
        assertTrue("280–1568" in e.message!! && "image too small" in e.message!!, e.message)
        assertTrue("typesafe_api_key" in assertFailsWith<IllegalStateException> { AutopilotFind(Fake(answer = FOUND), { null }).find("x", jpeg(600, 1200), 1, 1) }.message!!)
        assertTrue("at least 280" in assertFailsWith<IllegalStateException> { AutopilotFind.fit(jpeg(200, 900)) }.message!!)
    }
}
