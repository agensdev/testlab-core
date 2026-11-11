package no.agens.testlab.services.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.agens.testlab.core.jev.HttpPost
import no.agens.testlab.core.jev.HttpReply

/** The decision client's POST over Ktor (CIO engine, no extra native libraries). */
class KtorHttpPost(private val client: HttpClient = defaultClient()) : HttpPost {
    override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long, connectTimeoutMs: Long?): HttpReply {
        val r = client.post(url) {
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
                connectTimeoutMillis = connectTimeoutMs ?: timeoutMs
            }
            headers { headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> append(k, v) } }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return HttpReply(r.status.value, r.bodyAsText())
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            osTrust()
            install(HttpTimeout)
            expectSuccess = false
            followRedirects = false
        }
    }
}
