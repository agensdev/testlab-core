package no.agens.testlab.services.diag

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticsTest {
    private fun diag(handler: (String, Map<String, List<String>>) -> Triple<Int, String, Map<String, String>>) = Diagnostics(
        HttpClient(MockEngine { req ->
            val (status, body, headers) = handler(req.url.toString(), req.headers.entries().associate { it.key to it.value })
            respond(body, HttpStatusCode.fromValue(status), headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray()))
        }) { install(HttpTimeout); expectSuccess = false },
    )

    private val user = """{"login":"octocat"}"""

    @Test
    fun githubHappyClassic() = runTest {
        val d = diag { url, _ ->
            when {
                url.endsWith("/user") -> Triple(200, user, mapOf("X-OAuth-Scopes" to "repo, workflow", "github-authentication-token-expiration" to "2026-12-01"))
                else -> Triple(200, "{}", emptyMap())
            }
        }
        val c = d.github("ghp_x", "example-org", "example-org/shop-android")
        assertIs<Check.Ok>(c)
        assertEquals("Connected to GitHub as octocat", c.message)
        assertTrue(c.detail!!.let { it.contains("classic") && it.contains("2026-12-01") })
    }

    @Test
    fun githubSpecificProblems() = runTest {
        assertEquals("Invalid or expired GitHub token", diag { _, _ -> Triple(401, "{}", emptyMap()) }.github("x", "o", "o/r").message)
        val noRepo = diag { url, _ -> if (url.endsWith("/user")) Triple(200, user, mapOf("X-OAuth-Scopes" to "read:org")) else Triple(200, "{}", emptyMap()) }
        assertTrue("«repo» scope" in noRepo.github("x", "o", "o/r").message)
        val sso = diag { url, _ ->
            when {
                url.endsWith("/user") -> Triple(200, user, mapOf("X-OAuth-Scopes" to "repo"))
                url.contains("/orgs/") -> Triple(403, "{}", mapOf("X-GitHub-SSO" to "required; url=https://github.com/orgs/example-org/sso?authorization_request=abc"))
                else -> Triple(200, "{}", emptyMap())
            }
        }
        val c = sso.github("x", "example-org", "example-org/shop-android")
        assertIs<Check.Problem>(c)
        assertEquals("https://github.com/orgs/example-org/sso?authorization_request=abc", c.fixUrl)
        val fineGrainedNoActions = diag { url, _ ->
            when {
                url.endsWith("/user") -> Triple(200, user, emptyMap()) // fine-grained: no X-OAuth-Scopes
                url.contains("/actions/runs") -> Triple(403, "{}", mapOf("X-Accepted-GitHub-Permissions" to "actions=read"))
                else -> Triple(200, "{}", emptyMap())
            }
        }
        assertTrue("needs actions=read" in fineGrainedNoActions.github("x", "o", "o/r").message)
        val hidden = diag { url, _ -> if (url.endsWith("/user")) Triple(200, user, emptyMap()) else if (url.endsWith("/repos/o/r")) Triple(404, "{}", emptyMap()) else Triple(200, "{}", emptyMap()) }
        assertTrue("fine-grained" in hidden.github("x", "o", "o/r").message)
    }

    @Test
    fun jiraTokenTypes() = runTest {
        val ok = diag { url, h ->
            assertTrue(h["Authorization"]!!.single().startsWith("Basic "))
            Triple(200, """{"displayName":"Sam Tester","accountId":"abc"}""", emptyMap())
        }
        assertEquals("Connected to Jira as Sam Tester", ok.jira("example.atlassian.net", "me@example.com", "tok").message)
        val scoped = diag { url, _ ->
            when {
                url.contains("tenant_info") -> Triple(200, """{"cloudId":"9c989a80"}""", emptyMap())
                url.contains("api.atlassian.com/ex/jira/9c989a80") -> Triple(200, """{"displayName":"R"}""", emptyMap())
                else -> Triple(401, "{}", emptyMap())
            }
        }
        val c = scoped.jira("example.atlassian.net", "me@example.com", "scoped")
        assertIs<Check.Ok>(c)
        assertEquals("cloudId=9c989a80", c.detail)
        val wrong = diag { url, _ -> if (url.contains("tenant_info")) Triple(200, """{"cloudId":"x"}""", emptyMap()) else Triple(401, "{}", emptyMap()) }
        assertTrue("Create API token" in wrong.jira("s", "e", "t").message)
    }

    @Test
    fun jevAndOllama() = runTest {
        assertIs<Check.Ok>(diag { _, _ -> Triple(200, """{"model":"jev-1.13.0","answers":{}}""", emptyMap()) }.jev("k"))
        assertTrue("401" in diag { _, _ -> Triple(401, "", emptyMap()) }.jev("k").message)
        assertTrue("0.35" in diag { _, _ -> Triple(404, "404 page not found", emptyMap()) }.ollama("http://h:11434/v1/systemone|nimble:9b").message)
        assertTrue("ollama pull" in diag { _, _ -> Triple(404, """{"error":"model 'nimble:9b' not found"}""", emptyMap()) }.ollama("http://h/v1/systemone|nimble:9b").message)
    }
}
