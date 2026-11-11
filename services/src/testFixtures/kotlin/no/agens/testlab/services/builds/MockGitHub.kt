package no.agens.testlab.services.builds

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A scripted api.github.com: routes on "METHOD path?query" (longest prefix wins) and records
 * every request with its headers, so tests can check what was sent where.
 */
class MockGitHub {
    data class Seen(val method: String, val url: String, val headers: Map<String, List<String>>, val body: String? = null, val contentType: String? = null)

    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val routes = mutableListOf<Pair<String, suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData>>()

    fun on(prefix: String, handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) { routes += prefix to handler }

    fun json(prefix: String, body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) = on(prefix) {
        respond(body, HttpStatusCode.fromValue(status), headersOf(*(headers + ("Content-Type" to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
    }

    val client: HttpClient = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
        val key = "${req.method.value} ${if (req.url.host == "api.github.com") path else req.url.toString()}"
        seen += Seen(req.method.value, req.url.toString(), req.headers.entries().associate { it.key to it.value }, (req.body as? io.ktor.http.content.TextContent)?.text, req.body.contentType?.toString())
        // The longest matching prefix wins; between equal prefixes the newest registration does.
        val h = routes.withIndex().filter { key.startsWith(it.value.first) }.maxWithOrNull(compareBy({ it.value.first.length }, { it.index }))?.value?.second
            ?: return@MockEngine respond("""{"message":"Not Found: $key"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        h(req)
    }) {
        followRedirects = false
        expectSuccess = false
    }

    companion object {
        /** A zip like GitHub's artifact download: the APK inside. */
        fun artifactZip(apk: File, entryName: String = apk.name): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry(entryName)); z.write(apk.readBytes()); z.closeEntry() }
            return bos.toByteArray()
        }

        val FIXTURE = File("../tools/testapp/fixture-v1.apk")
    }
}
