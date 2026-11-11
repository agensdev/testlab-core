package no.agens.testlab.services.diag

import no.agens.testlab.services.i18n.tr
import no.agens.testlab.services.http.osTrust
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * "Test connection" for each integration: the cheapest identity call, then one
 * specific message per real failure (wrong token type, missing scope, SSO not authorized…) with
 * the link that fixes it. Runs as the user types, debounced by the caller.
 */
sealed interface Check {
    val message: String

    data class Ok(override val message: String, val detail: String? = null) : Check
    data class Problem(override val message: String, val fixUrl: String? = null) : Check
    data class Unreachable(override val message: String) : Check
}

class Diagnostics(private val http: HttpClient) {

    /** GitHub: token valid, right type and scope, SSO-authorized for the org, can read the repo's Actions. */
    suspend fun github(token: String, org: String, repo: String, api: String = GH): Check {
        if (token.isBlank()) return Check.Problem(tr("Add a GitHub token"), TOKEN_PAGE)
        val auth = "Bearer ${token.trim()}"
        val user = try { http.get("${api.trimEnd('/')}/user") { gh(auth) } } catch (e: Exception) { return Check.Unreachable(tr("GitHub is not reachable: {0}", e.message)) }
        if (user.status.value == 401) return Check.Problem(tr("Invalid or expired GitHub token"), TOKEN_PAGE)
        if (user.status.value != 200) return Check.Problem(tr("GitHub answered {0} for /user", user.status.value))
        val login = Json.parseToJsonElement(user.bodyAsText()).jsonObject["login"]?.jsonPrimitive?.content ?: "?"
        val scopes = user.headers["X-OAuth-Scopes"]
        val classic = scopes != null
        if (classic && scopes.split(',').map { it.trim() }.none { it == "repo" }) {
            return Check.Problem(tr("The token is valid ({0}) but lacks the «repo» scope", login), TOKEN_PAGE)
        }
        val expires = user.headers["github-authentication-token-expiration"]
        val kind = if (classic) "classic token" else "fine-grained token"
        // No project yet (first-time setup): the token itself is all there is to check.
        if (repo.isBlank()) return Check.Ok(tr("Connected to GitHub as {0}", login), listOfNotNull(kind, expires?.let { "expires $it" }).joinToString(", "))
        if (org.isNotBlank()) {
            val orgResp = http.get("${api.trimEnd('/')}/orgs/$org") { gh(auth) }
            ssoUrl(orgResp)?.let { return Check.Problem(tr("The token is valid ({0}) but not authorized for {1} with «Configure SSO»", login, org), it) }
        }
        val repoResp = http.get("${api.trimEnd('/')}/repos/$repo") { gh(auth) }
        ssoUrl(repoResp)?.let { return Check.Problem(tr("Authorize the token for {0} («Configure SSO»)", org), it) }
        if (repoResp.status.value == 404) {
            return Check.Problem(
                if (classic) "$login cannot see $repo (not a member, or the token was made before you joined)"
                else "This fine-grained token cannot see $repo: give it access to the repository (it may be waiting for org approval)",
                TOKEN_PAGE,
            )
        }
        val runs = http.get("${api.trimEnd('/')}/repos/$repo/actions/runs?per_page=1") { gh(auth) }
        if (runs.status.value == 403 || runs.status.value == 404) {
            val need = runs.headers["X-Accepted-GitHub-Permissions"] ?: "actions=read"
            return Check.Problem(tr("The token can see {0} but not its builds (needs {1})", repo, need), TOKEN_PAGE)
        }
        return Check.Ok(tr("Connected to GitHub as {0}", login), listOfNotNull(kind, expires?.let { "expires $it" }).joinToString(", "))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.gh(auth: String) {
        header("Authorization", auth)
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    /** `X-GitHub-SSO: required; url=https://github.com/orgs/…/sso?authorization_request=…` */
    private fun ssoUrl(r: HttpResponse): String? {
        if (r.status.value != 403) return null
        val h = r.headers["X-GitHub-SSO"] ?: return null
        return h.substringAfter("url=", "").trim().ifEmpty { "https://github.com/settings/tokens" }
    }

    /** Jira Cloud: Basic e-mail:token on /myself; a scoped token is tried through the API gateway. */
    /** [base] is where the site's REST API is (the `connections` settings can point a site elsewhere). */
    suspend fun jira(site: String, email: String, token: String, base: String = "https://$site"): Check {
        if (email.isBlank() || token.isBlank()) return Check.Problem(tr("Add your Jira e-mail and API token"), JIRA_TOKEN_PAGE)
        val basic = "Basic " + Base64.getEncoder().encodeToString("${email.trim()}:${token.trim()}".toByteArray())
        val r = try { http.get("${base.trimEnd('/')}/rest/api/3/myself") { header("Authorization", basic); header("Accept", "application/json") } }
        catch (e: Exception) { return Check.Unreachable(tr("{0} is not reachable: {1}", site, e.message)) }
        if (r.status.value == 200) {
            val me = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            return Check.Ok(tr("Connected to Jira as {0}", me["displayName"]?.jsonPrimitive?.content ?: email), me["accountId"]?.jsonPrimitive?.content)
        }
        if (r.status.value == 401 || r.status.value == 403) {
            // A scoped token only works through the gateway; tell which case it is.
            val cloudId = cloudId(site)
            if (cloudId != null) {
                val g = http.get("https://api.atlassian.com/ex/jira/$cloudId/rest/api/3/myself") { header("Authorization", basic) }
                if (g.status.value == 200) return Check.Ok(tr("Connected to Jira through the API gateway (scoped token)"), "cloudId=$cloudId")
            }
            return Check.Problem(tr("Jira refused the token. Use an API token made with «Create API token», not «with scopes», and your work e-mail"), JIRA_TOKEN_PAGE)
        }
        return Check.Problem(tr("Jira answered {0}", r.status.value))
    }

    suspend fun cloudId(site: String): String? = try {
        Json.parseToJsonElement(http.get("https://$site/_edge/tenant_info").bodyAsText()).jsonObject["cloudId"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    /** TypeSafe System One: a two-option question, so a working key costs a few hundred tokens. */
    suspend fun jev(key: String, url: String = "https://api.typesafe.ai/v1/systemone"): Check {
        if (key.isBlank()) return Check.Problem(tr("Add your TypeSafe API key (or use a local Ollama backend)"), "https://typesafe.ai")
        val r = try {
            http.post(url) {
                header("Authorization", "Bearer ${key.trim()}")
                contentType(ContentType.Application.Json)
                setBody(PROBE)
                timeout { requestTimeoutMillis = 15_000 }
            }
        } catch (e: Exception) {
            return Check.Unreachable(tr("TypeSafe is not reachable: {0}", e.message))
        }
        return when (r.status.value) {
            200 -> Check.Ok(tr("TypeSafe Jev answers"), model(r.bodyAsText()))
            401 -> Check.Problem(tr("TypeSafe rejected the key (401)"), "https://typesafe.ai")
            422 -> Check.Problem(tr("TypeSafe rejected the request (422): {0}", r.bodyAsText().take(120)))
            429, 529 -> Check.Problem(tr("TypeSafe is rate-limiting or overloaded ({0}); try again shortly", r.status.value))
            else -> Check.Problem(tr("TypeSafe answered {0}", r.status.value))
        }
    }

    /**
     * An OpenAI API key, by listing the models (free, no tokens): 401 = a wrong or revoked key,
     * 429 with insufficient_quota = the account has no API credit (API billing is separate from ChatGPT).
     */
    suspend fun openai(key: String, api: String = "https://api.openai.com/v1"): Check {
        if (key.isBlank()) return Check.Problem(tr("Add an OpenAI API key"), OPENAI_KEY_PAGE)
        val r = try { http.get("${api.trimEnd('/')}/models") { header("Authorization", "Bearer ${key.trim()}"); timeout { requestTimeoutMillis = 15_000 } } }
        catch (e: Exception) { return Check.Unreachable(tr("OpenAI is not reachable: {0}", e.message)) }
        val body = runCatching { r.bodyAsText() }.getOrDefault("")
        return when {
            r.status.value == 200 -> Check.Ok(tr("OpenAI accepts the key"))
            r.status.value == 401 -> Check.Problem(tr("OpenAI rejected the key (401): make a new secret key"), OPENAI_KEY_PAGE)
            r.status.value == 403 -> Check.Problem(tr("The key works but may not use the API here (403): a restricted key needs model access, or the project is limited"), OPENAI_KEY_PAGE)
            r.status.value == 429 && "insufficient_quota" in body -> Check.Problem(tr("The key works, but the account has no API credit: add a payment method or credit (API billing is separate from a ChatGPT plan)"), OPENAI_BILLING_PAGE)
            r.status.value == 429 -> Check.Problem(tr("OpenAI is rate-limiting this key (429); try again shortly"))
            else -> Check.Problem(tr("OpenAI answered {0}", r.status.value))
        }
    }

    /** One Ollama `url|model` entry: reachable, ≥ 0.35 (has /v1/systemone), model pulled. */
    suspend fun ollama(entry: String): Check {
        val url = entry.substringBefore('|').trim()
        val model = entry.substringAfter('|', "").trim().ifEmpty { "nimble:9b" }
        val body = PROBE.replace("\"jev-latest\"", "\"$model\"")
        val r = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
                timeout { connectTimeoutMillis = 3_000; requestTimeoutMillis = 120_000 }
            }
        } catch (e: Exception) {
            return Check.Unreachable(tr("No Ollama at {0} ({1})", url, e.message))
        }
        return when (r.status.value) {
            200 -> Check.Ok(tr("{0} answers at {1}", model, url), model(r.bodyAsText()))
            404 -> Check.Problem(
                if ("model" in r.bodyAsText()) "Ollama at $url does not have $model: run `ollama pull $model`"
                else "Ollama at $url has no System One API: it needs version 0.35 or newer",
            )
            else -> Check.Problem(tr("Ollama at {0} answered {1}: {2}", url, r.status.value, r.bodyAsText().take(120)))
        }
    }

    private fun model(text: String): String? = runCatching { (Json.parseToJsonElement(text) as JsonObject)["model"]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        /** The app's instance: short timeouts, no exceptions on 4xx (each status is a message). */
        fun create(): Diagnostics = Diagnostics(
            HttpClient(io.ktor.client.engine.cio.CIO) {
                osTrust()
                install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
                expectSuccess = false
            },
        )

        const val GH = "https://api.github.com"
        val TOKEN_PAGE = "https://github.com/settings/tokens/new?scopes=repo&description=" + java.net.URLEncoder.encode(no.agens.testlab.AppIdentity.NAME, Charsets.UTF_8).replace("+", "%20")
        const val JIRA_TOKEN_PAGE = "https://id.atlassian.com/manage-profile/security/api-tokens"
        /** Where an OpenAI API key is made (Dashboard → API keys → Create new secret key). */
        const val OPENAI_KEY_PAGE = "https://platform.openai.com/api-keys"
        const val OPENAI_BILLING_PAGE = "https://platform.openai.com/settings/organization/billing/overview"
        private const val PROBE =
            """{"model":"jev-latest","state":{"goal":"connection test"},"questions":{"next":{"type":"choice","instructions":"Pick a.","criteria":{"a":"option a","b":"option b"}}}}"""
    }
}
