package no.agens.testlab.services.jira

import no.agens.testlab.services.http.osTrust
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.util.cio.readChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URLEncoder
import java.util.Base64

/** A Jira failure worded for the user, with the page that fixes it when there is one. */
class JiraError(val status: Int, message: String, val fixUrl: String? = null) : RuntimeException(message)

data class JiraUser(val accountId: String, val displayName: String, val email: String? = null)

data class JiraAttachment(val id: String, val filename: String, val mimeType: String, val size: Long, val created: String, val author: String?)

data class JiraComment(val id: String, val author: String?, val created: String, val markdown: String)

data class JiraIssue(
    val id: String,
    val key: String,
    val summary: String,
    val type: String,
    val status: String,
    val statusCategory: String,
    val priority: String?,
    val assignee: JiraUser?,
    val reporter: JiraUser?,
    val labels: List<String>,
    val components: List<String>,
    val fixVersions: List<String>,
    val parent: String?,
    val created: String,
    val updated: String,
    val description: String,
    val comments: List<JiraComment>,
    val commentTotal: Int,
    val attachments: List<JiraAttachment>,
    val links: List<String>,
    val url: String,
)

data class SearchPage(val issues: List<JiraIssue>, val nextPageToken: String?)

/** A pull request or branch Jira's development panel knows about. */
data class DevLink(val kind: String, val name: String, val url: String, val status: String?, val branch: String?, val number: Int?)

data class AttachmentLimits(val enabled: Boolean, val uploadLimit: Long)

/**
 * Jira Cloud REST v3 with the user's e-mail and API token (Basic). A scoped token goes through
 * api.atlassian.com/ex/jira/{cloudId}; [base] decides. Descriptions and comments come back as
 * Markdown and go out as ADF ([Adf]). Labels change only by add/remove, never by replacing the
 * set, so a write never drops someone else's label.
 */
class Jira(
    private val http: HttpClient,
    private val base: () -> String,
    private val site: () -> String,
    private val email: () -> String?,
    private val token: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun HttpRequestBuilder.auth() {
        val e = email()?.trim().orEmpty()
        val t = token()?.trim().orEmpty()
        if (e.isEmpty() || t.isEmpty()) throw JiraError(401, "No Jira e-mail or API token: add them on the setup page (`testlab open setup`)", TOKEN_PAGE)
        header(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("$e:$t".toByteArray()))
        header(HttpHeaders.Accept, "application/json")
    }

    private suspend fun check(r: HttpResponse, what: String): HttpResponse {
        val s = r.status.value
        if (s in 200..299) return r
        val body = runCatching { r.bodyAsText() }.getOrDefault("")
        val messages = runCatching {
            val o = json.parseToJsonElement(body) as JsonObject
            (o["errorMessages"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content } +
                (o["errors"] as? JsonObject)?.entries.orEmpty().map { "${it.key}: ${(it.value as? JsonPrimitive)?.content ?: it.value}" } +
                listOfNotNull((o["message"] as? JsonPrimitive)?.content)
        }.getOrDefault(emptyList()).joinToString("; ")
        throw when (s) {
            401 -> JiraError(s, "Jira rejected the e-mail or API token (or it expired): check them on the setup page (`testlab open setup`)", TOKEN_PAGE)
            403 -> JiraError(s, "Jira does not let this account $what${if (messages.isNotEmpty()) ": $messages" else ""}")
            404 -> JiraError(s, "Not found: $what (or this account can't see it)")
            429 -> JiraError(s, "Jira is rate-limiting this account; try again in a minute")
            else -> JiraError(s, "Jira answered $s for $what${if (messages.isNotEmpty()) ": $messages" else ""}")
        }
    }

    @Volatile private var verified: Int? = null

    /**
     * Jira Cloud serves reads with a wrong e-mail or token as anonymous: «Not found» and empty searches
     * instead of a 401. So the login is checked on /myself once per e-mail, token and URL before the
     * first call, and a rejected one is said as such.
     */
    private suspend fun verify() {
        val fp = listOf(base(), email()?.trim(), token()?.trim()).hashCode()
        if (verified == fp) return
        val r = http.get(base() + "/rest/api/3/myself") { auth() }
        when (r.status.value) {
            in 200..299 -> verified = fp
            401 -> throw JiraError(401, "Jira rejected the e-mail or API token (or it expired): check them on the setup page (`testlab open setup`), or `secrets set jira.email` / `secrets set jira.token`. " +
                "The token must belong to that e-mail's Atlassian account; a scoped token needs `testlab status` once to find the API gateway.", TOKEN_PAGE)
        }
    }

    private suspend fun getJson(path: String, what: String): JsonElement {
        if (path != "/rest/api/3/myself") verify()
        return json.parseToJsonElement(check(http.get(base() + path) { auth() }, what).bodyAsText())
    }

    private suspend fun sendJson(method: String, path: String, body: JsonElement, what: String): String {
        verify()
        val r = when (method) {
            "PUT" -> http.put(base() + path) { auth(); contentType(ContentType.Application.Json); setBody(body.toString()) }
            else -> http.post(base() + path) { auth(); contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        return check(r, what).bodyAsText()
    }

    fun browseUrl(key: String) = "https://${site()}/browse/$key"

    suspend fun myself(): JiraUser = (getJson("/rest/api/3/myself", "read your profile") as JsonObject).toUser()!!

    /** Projects the account can see: key and name. */
    suspend fun projects(max: Int = 100): List<Pair<String, String>> =
        ((getJson("/rest/api/3/project/search?maxResults=$max&orderBy=name", "list projects") as JsonObject)["values"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.map { it.str("key") to it.str("name") }

    /** The site's labels (Jira keeps one global list). */
    suspend fun labels(max: Int = 1000): List<String> =
        ((getJson("/rest/api/3/label?maxResults=$max", "list labels") as JsonObject)["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    suspend fun issue(key: String, maxComments: Int = 20): JiraIssue {
        val k = checkKey(key)
        val o = getJson("/rest/api/3/issue/${enc(k)}?fields=$FIELDS", "read $k") as JsonObject
        val issue = o.toIssue()
        if (issue.commentTotal <= issue.comments.size || maxComments <= issue.comments.size) return issue.copy(comments = issue.comments.takeLast(maxComments))
        // The issue carries the first comments only; the newest ones matter for a devtest.
        val start = maxOf(0, issue.commentTotal - maxComments)
        val c = getJson("/rest/api/3/issue/${enc(k)}/comment?startAt=$start&maxResults=$maxComments&orderBy=created", "read the comments of $k") as JsonObject
        return issue.copy(comments = (c["comments"] as? JsonArray).orEmpty().map { (it as JsonObject).toComment() })
    }

    /** One page of `search/jql` (the bounded search; pages by token, not offset). */
    suspend fun search(jql: String, max: Int = 20, nextPageToken: String? = null): SearchPage {
        val body = buildJsonObject {
            put("jql", jql)
            put("maxResults", max.coerceIn(1, 100))
            putJsonArray("fields") { FIELDS.split(',').filter { it != "comment" && it != "description" }.forEach { add(JsonPrimitive(it)) } }
            nextPageToken?.let { put("nextPageToken", it) }
        }
        val o = json.parseToJsonElement(sendJson("POST", "/rest/api/3/search/jql", body, "search ($jql)")) as JsonObject
        return SearchPage((o["issues"] as? JsonArray).orEmpty().map { (it as JsonObject).toIssue() }, o.str("nextPageToken").ifEmpty { null }?.takeUnless { o.bool("isLast") })
    }

    /** Posts [markdown] as a comment; returns the comment id. */
    suspend fun addComment(key: String, markdown: String): String {
        val k = checkKey(key)
        val o = json.parseToJsonElement(sendJson("POST", "/rest/api/3/issue/${enc(k)}/comment", buildJsonObject { put("body", Adf.fromMarkdown(markdown)) }, "comment on $k")) as JsonObject
        return o.str("id")
    }

    suspend fun attachmentLimits(): AttachmentLimits {
        val o = getJson("/rest/api/3/attachment/meta", "read the attachment settings") as JsonObject
        return AttachmentLimits(o.bool("enabled"), (o["uploadLimit"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

    /** Uploads [files] to [key], one request each so a size failure names its file. */
    suspend fun attach(key: String, files: List<File>): List<JiraAttachment> {
        val k = checkKey(key)
        val limits = attachmentLimits()
        if (!limits.enabled) throw JiraError(403, "Attachments are turned off on ${site()}")
        files.firstOrNull { limits.uploadLimit > 0 && it.length() > limits.uploadLimit }?.let {
            throw JiraError(413, "${it.name} is ${it.length() / 1_000_000} MB; ${site()} takes at most ${limits.uploadLimit / 1_000_000} MB per file")
        }
        return files.flatMap { f ->
            val r = http.post(base() + "/rest/api/3/issue/${enc(k)}/attachments") {
                auth()
                header("X-Atlassian-Token", "no-check")
                setBody(MultiPartFormDataContent(formData {
                    append("file", ChannelProvider(f.length()) { f.readChannel() }, Headers.build {
                        append(HttpHeaders.ContentType, mime(f.name))
                        append(HttpHeaders.ContentDisposition, "filename=\"${f.name.replace("\"", "")}\"")
                    })
                }))
            }
            (json.parseToJsonElement(check(r, "attach ${f.name} to $k").bodyAsText()) as JsonArray).map { (it as JsonObject).toAttachment() }
        }
    }

    suspend fun updateLabels(key: String, add: List<String>, remove: List<String>) {
        val k = checkKey(key)
        add.plus(remove).firstOrNull { it.isBlank() || it.any(Char::isWhitespace) }?.let { throw IllegalArgumentException("a Jira label has no spaces: '$it'") }
        val body = buildJsonObject {
            putJsonObject("update") {
                put("labels", buildJsonArray {
                    add.forEach { add(buildJsonObject { put("add", it) }) }
                    remove.forEach { add(buildJsonObject { put("remove", it) }) }
                })
            }
        }
        sendJson("PUT", "/rest/api/3/issue/${enc(k)}", body, "change the labels of $k")
    }

    /** [accountId] null unassigns. */
    suspend fun assign(key: String, accountId: String?) {
        val k = checkKey(key)
        sendJson("PUT", "/rest/api/3/issue/${enc(k)}/assignee", buildJsonObject { put("accountId", accountId?.let { JsonPrimitive(it) } ?: JsonNull) }, "assign $k")
    }

    /**
     * Pull requests and branches from Jira's development panel. The dev-status API is
     * undocumented, so every failure just means "no links".
     */
    suspend fun devLinks(issueId: String): List<DevLink> = runCatching {
        val summary = getJson("/rest/dev-status/latest/issue/summary?issueId=${enc(issueId)}", "read the development panel") as JsonObject
        val types = listOf("pullrequest", "branch").flatMap { kind ->
            ((summary.obj("summary").obj(kind).obj("byInstanceType")).keys).map { it to kind }
        }
        types.flatMap { (app, kind) ->
            runCatching {
                val d = getJson("/rest/dev-status/latest/issue/detail?issueId=${enc(issueId)}&applicationType=${enc(app)}&dataType=$kind", "read linked ${kind}es") as JsonObject
                (d["detail"] as? JsonArray).orEmpty().flatMap { det ->
                    val o = det as JsonObject
                    if (kind == "pullrequest") (o["pullRequests"] as? JsonArray).orEmpty().map { p ->
                        val pr = p as JsonObject
                        DevLink("pr", pr.str("name"), pr.str("url"), pr.str("status").ifEmpty { null }, pr.obj("source").str("branch").ifEmpty { null }, pr.str("id").trimStart('#').toIntOrNull()
                            ?: Regex("/pull/(\\d+)").find(pr.str("url"))?.groupValues?.get(1)?.toIntOrNull())
                    } else (o["branches"] as? JsonArray).orEmpty().map { b ->
                        val br = b as JsonObject
                        DevLink("branch", br.str("name"), br.str("url"), null, br.str("name"), null)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }.getOrDefault(emptyList())

    // --- parsing ---

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.names(k: String, field: String = "name"): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str(field)?.ifEmpty { null } ?: (it as? JsonPrimitive)?.content }

    private fun JsonElement?.toUser(): JiraUser? = (this as? JsonObject)?.let { JiraUser(it.str("accountId"), it.str("displayName"), it.str("emailAddress").ifEmpty { null }) }

    private fun JsonObject.toComment() = JiraComment(str("id"), (this["author"]).toUser()?.displayName, str("created"), Adf.toMarkdown(this["body"]))

    private fun JsonObject.toAttachment() = JiraAttachment(
        str("id"), str("filename"), str("mimeType"), (this["size"] as? JsonPrimitive)?.longOrNull ?: 0, str("created"), this["author"].toUser()?.displayName,
    )

    private fun JsonObject.toIssue(): JiraIssue {
        val f = obj("fields")
        val comment = f.obj("comment")
        val key = str("key")
        val links = (f["issuelinks"] as? JsonArray).orEmpty().mapNotNull { l ->
            val o = l as JsonObject
            val type = o.obj("type")
            o.obj("outwardIssue").str("key").ifEmpty { null }?.let { "${type.str("outward")} $it" }
                ?: o.obj("inwardIssue").str("key").ifEmpty { null }?.let { "${type.str("inward")} $it" }
        }
        return JiraIssue(
            id = str("id"),
            key = key,
            summary = f.str("summary"),
            type = f.obj("issuetype").str("name"),
            status = f.obj("status").str("name"),
            statusCategory = f.obj("status").obj("statusCategory").str("key"),
            priority = f.obj("priority").str("name").ifEmpty { null },
            assignee = f["assignee"].toUser(),
            reporter = f["reporter"].toUser(),
            labels = f.names("labels"),
            components = f.names("components"),
            fixVersions = f.names("fixVersions"),
            parent = f.obj("parent").str("key").ifEmpty { null },
            created = f.str("created"),
            updated = f.str("updated"),
            description = Adf.toMarkdown(f["description"]),
            comments = (comment["comments"] as? JsonArray).orEmpty().map { (it as JsonObject).toComment() },
            commentTotal = (comment["total"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
            attachments = (f["attachment"] as? JsonArray).orEmpty().map { (it as JsonObject).toAttachment() },
            links = links,
            url = browseUrl(key),
        )
    }

    companion object {
        const val TOKEN_PAGE = "https://id.atlassian.com/manage-profile/security/api-tokens"
        private const val FIELDS = "summary,status,issuetype,priority,assignee,reporter,labels,components,fixVersions,parent,created,updated,description,comment,attachment,issuelinks"
        private val KEY = Regex("^[A-Z][A-Z0-9_]+-\\d+$")

        /** Accepts `PROJ-123`, `proj-123` or a browse URL. */
        fun parseKey(s: String): String? {
            val t = s.trim().substringAfterLast("/browse/").substringBefore('?').substringBefore('#').uppercase()
            return Regex("[A-Z][A-Z0-9_]+-\\d+").find(t)?.value?.takeIf { KEY.matches(it) }
        }

        fun checkKey(s: String): String = parseKey(s) ?: throw IllegalArgumentException("'$s' is not a Jira issue key (like PROJ-123)")

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

        fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "html" -> "text/html"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "txt", "log" -> "text/plain"
            else -> "application/octet-stream"
        }

        fun httpClient(): HttpClient = HttpClient(CIO) {
            osTrust()
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 120_000 }
        }
    }
}
