package no.agens.testlab.device.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A WebDriver error: [error] is the W3C code (`no such element`, `invalid session id`…). */
class WebDriverException(val error: String, message: String) : RuntimeException(message)

/**
 * The few W3C WebDriver calls the iOS bridge needs, against Appium's XCUITest driver, in plain HTTP
 * (no client library). One session per device, kept for as long as the bridge runs.
 *
 * After [goDirect] the calls made on every step (the screen's tree, screenshots, taps, keys, finding
 * elements) go straight to WebDriverAgent, on the port Appium started it on, under the session
 * Appium opened there: Appium's proxy hop is left out. Anything else, and every call after
 * WebDriverAgent stops answering directly, goes through Appium as before.
 */
class WebDriver(private val base: String, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
    @Volatile var sessionId: String? = null
        private set

    /** WebDriverAgent's own address and session (see [goDirect]); null: everything through Appium. */
    @Volatile private var direct: Pair<String, String>? = null

    /** Whether the hot calls go straight to WebDriverAgent. */
    val isDirect: Boolean get() = direct != null

    /**
     * Attributes left out of a direct `/source` (WebDriverAgent's `excluded_attributes`): `visible` is
     * the one that costs, as WebDriverAgent works it out element by element ([XcuiTree] then judges
     * by the bounds).
     */
    @Volatile var sourceExcluded: String? = null

    private fun request(method: String, path: String, body: JsonElement? = null, timeoutMs: Long = 60_000, at: String = base): JsonElement {
        val b = HttpRequest.newBuilder(URI.create(at.trimEnd('/') + path)).timeout(Duration.ofMillis(timeoutMs)).header("Content-Type", "application/json")
        val req = when (method) {
            "GET" -> b.GET()
            "DELETE" -> b.DELETE()
            else -> b.POST(HttpRequest.BodyPublishers.ofString((body ?: JsonObject(emptyMap())).toString()))
        }.build()
        val res = try { http.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: java.io.IOException) {
            throw WebDriverException("unreachable", "${if (at == base) "Appium" else "WebDriverAgent"} does not answer at $at: ${e.message}")
        }
        val json = runCatching { Json.parseToJsonElement(res.body()) }.getOrNull()
        val value = (json as? JsonObject)?.get("value") ?: JsonNull
        if (res.statusCode() !in 200..299) {
            val v = value as? JsonObject
            val error = (v?.get("error") as? JsonPrimitive)?.content ?: "http ${res.statusCode()}"
            val message = (v?.get("message") as? JsonPrimitive)?.content?.lineSequence()?.firstOrNull() ?: res.body().take(200)
            throw WebDriverException(error, "$error: $message")
        }
        return value
    }

    private fun s(path: String) = "/session/" + (sessionId ?: throw WebDriverException("invalid session id", "no WebDriver session")) + path

    fun status(): Boolean = runCatching { request("GET", "/status", timeoutMs = 5_000); true }.getOrDefault(false)

    /**
     * Sends the hot calls straight to WebDriverAgent at [wdaUrl] (`http://127.0.0.1:<wdaLocalPort>`;
     * Appium forwards a real phone's port there) under the session Appium opened in it, which its
     * `/status` names. False, and nothing changes, when it can't be reached that way.
     */
    fun goDirect(wdaUrl: String): Boolean {
        val id = runCatching { wdaSession(wdaUrl) }.getOrNull() ?: return false
        direct = wdaUrl to id
        return true
    }

    /** `/status`'s `sessionId`, which WebDriverAgent puts beside `value`, not in it. */
    private fun wdaSession(wdaUrl: String): String? {
        val res = http.send(HttpRequest.newBuilder(URI.create(wdaUrl.trimEnd('/') + "/status")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        return ((Json.parseToJsonElement(res.body()) as? JsonObject)?.get("sessionId") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * A hot call: straight to WebDriverAgent when [goDirect] took, else through Appium. A direct call
     * that can't reach it, or whose session is gone (WebDriverAgent restarted), looks the session up
     * once more; when that fails too, this and every later call go through Appium.
     */
    private fun hot(
        method: String, path: String, body: JsonElement? = null, timeoutMs: Long = 60_000, wdaPath: String = path, sessionless: Boolean = false,
        viaAppium: () -> JsonElement = { request(method, s(path), body, timeoutMs) },
    ): JsonElement {
        val d = direct ?: return viaAppium()
        fun at(id: String) = if (sessionless) wdaPath else "/session/$id$wdaPath"
        try {
            return request(method, at(d.second), body, timeoutMs, d.first)
        } catch (e: WebDriverException) {
            if (e.error != "unreachable" && e.error != "invalid session id") throw e
        }
        val again = runCatching { wdaSession(d.first) }.getOrNull()
        if (again != null && again != d.second) {
            direct = d.first to again
            runCatching { return request(method, at(again), body, timeoutMs, d.first) }
        }
        direct = null
        return viaAppium()
    }

    /** Opens the session; WebDriverAgent's first build on a machine takes minutes, hence the long wait. */
    fun newSession(capabilities: JsonObject, timeoutMs: Long = 600_000): String {
        val v = request("POST", "/session", buildJsonObject { putJsonObject("capabilities") { put("alwaysMatch", capabilities) } }, timeoutMs) as JsonObject
        return (v["sessionId"] as JsonPrimitive).content.also { sessionId = it }
    }

    fun deleteSession() {
        val id = sessionId ?: return
        sessionId = null
        runCatching { request("DELETE", "/session/$id", timeoutMs = 15_000) }
    }

    /** Whether the session still answers (`window/rect`: Appium 3 has no `window/size`). */
    fun alive(): Boolean = sessionId != null && runCatching { request("GET", s("/window/rect"), timeoutMs = 10_000); true }.getOrDefault(false)

    /** The screen size in points. */
    fun windowRect(): Pair<Int, Int> {
        // WebDriverAgent has /window/size; Appium 3 only /window/rect.
        val v = hot("GET", "/window/rect", wdaPath = "/window/size") as JsonObject
        fun n(k: String) = (v[k] as JsonPrimitive).content.toDouble().toInt()
        return n("width") to n("height")
    }

    fun source(): String {
        val q = sourceExcluded?.let { "?excluded_attributes=$it" }.orEmpty()
        return (hot("GET", "/source", timeoutMs = 30_000, wdaPath = "/source$q") as JsonPrimitive).content
    }

    /** The screen as PNG (WebDriverAgent's own screenshot: what a real device has instead of simctl). */
    fun screenshot(): ByteArray = PhoneTarget.decode((hot("GET", "/screenshot", timeoutMs = 30_000) as JsonPrimitive).content)

    /** The app in front (bundleId, name, pid): WebDriverAgent's /wda/activeAppInfo. */
    fun activeApp(): String? = runCatching {
        ((hot("GET", "/wda/activeAppInfo", sessionless = true, viaAppium = { mobile("activeAppInfo") }) as? JsonObject)?.get("bundleId") as? JsonPrimitive)?.content
    }.getOrNull()

    fun terminate(bundleId: String) {
        hot("POST", "/wda/apps/terminate", buildJsonObject { put("bundleId", bundleId) }, viaAppium = { mobile("terminateApp", buildJsonObject { put("bundleId", bundleId) }) })
    }

    /** The home screen: WebDriverAgent's /wda/homescreen. */
    fun homescreen() {
        hot("POST", "/wda/homescreen", sessionless = true, viaAppium = { mobile("pressButton", buildJsonObject { put("name", "home") }) })
    }

    /** Appium/WebDriverAgent settings, e.g. mjpegServerFramerate and mjpegScalingFactor for the MJPEG stream. */
    fun appiumSettings(settings: JsonObject) {
        hot("POST", "/appium/settings", buildJsonObject { put("settings", settings) })
    }

    /** WebDriverAgent's orientation (PORTRAIT, LANDSCAPE, UIA_DEVICE_ORIENTATION_LANDSCAPERIGHT…); /orientation works without a session. */
    fun orientation(): String = (hot("GET", "/orientation", sessionless = true, viaAppium = { request("GET", s("/orientation")) }) as? JsonPrimitive)?.content.orEmpty()

    fun setOrientation(value: String) {
        hot("POST", "/orientation", buildJsonObject { put("orientation", value) })
    }

    /** A touch in points: down, [holdMs], up. */
    fun tap(x: Int, y: Int, holdMs: Int = 60) = pointer(listOf(Triple("pointerMove", x, y), Triple("pointerDown", 0, 0), Triple("pause", holdMs, 0), Triple("pointerUp", 0, 0)))

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int) =
        pointer(listOf(Triple("pointerMove", x1, y1), Triple("pointerDown", 0, 0), Triple("pause", 50, 0), Triple("pointerMoveTo", x2, y2), Triple("pointerUp", 0, 0)), moveMs = durMs)

    /**
     * A finger along a path, in points: move to the first point, down, [holdMs] still, then each next
     * point in its leg's time ([legMs], one per leg), up. W3C actions, pointerType touch.
     */
    fun drag(points: List<Pair<Int, Int>>, legMs: List<Int>, holdMs: Int) {
        require(points.size >= 2 && legMs.size == points.size - 1)
        hot("POST", "/actions", buildJsonObject {
            putJsonArray("actions") {
                addJsonObject {
                    put("type", "pointer"); put("id", "finger1")
                    putJsonObject("parameters") { put("pointerType", "touch") }
                    putJsonArray("actions") {
                        addJsonObject { put("type", "pointerMove"); put("duration", 0); put("x", points[0].first); put("y", points[0].second) }
                        addJsonObject { put("type", "pointerDown"); put("button", 0) }
                        if (holdMs > 0) addJsonObject { put("type", "pause"); put("duration", holdMs) }
                        for (i in 1 until points.size) addJsonObject { put("type", "pointerMove"); put("duration", legMs[i - 1]); put("x", points[i].first); put("y", points[i].second) }
                        addJsonObject { put("type", "pointerUp"); put("button", 0) }
                    }
                }
            }
        }, timeoutMs = 60_000L + holdMs + legMs.sum())
        if (direct == null) runCatching { request("DELETE", s("/actions"), timeoutMs = 10_000) }
    }

    private fun pointer(steps: List<Triple<String, Int, Int>>, moveMs: Int = 0) {
        hot("POST", "/actions", buildJsonObject {
            putJsonArray("actions") {
                addJsonObject {
                    put("type", "pointer"); put("id", "finger1")
                    putJsonObject("parameters") { put("pointerType", "touch") }
                    putJsonArray("actions") {
                        for ((t, a, b) in steps) addJsonObject {
                            when (t) {
                                "pointerMove" -> { put("type", "pointerMove"); put("duration", 0); put("x", a); put("y", b) }
                                "pointerMoveTo" -> { put("type", "pointerMove"); put("duration", moveMs); put("x", a); put("y", b) }
                                "pause" -> { put("type", "pause"); put("duration", a) }
                                else -> { put("type", t); put("button", 0) }
                            }
                        }
                    }
                }
            }
        })
        // WebDriverAgent releases the pointer itself; Appium wants it said.
        if (direct == null) runCatching { request("DELETE", s("/actions"), timeoutMs = 10_000) }
    }

    /** Keys to the focused element: "\n" is return, "\b" backspace. */
    fun keys(text: String) {
        hot("POST", "/wda/keys", buildJsonObject { putJsonArray("value") { for (c in text.codePoints().toArray()) add(JsonPrimitive(String(Character.toChars(c)))) } })
    }

    /** `mobile:` extension commands (pressButton, activeAppInfo, hideKeyboard…). */
    fun mobile(command: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        val viaAppium = { request("POST", s("/execute/sync"), buildJsonObject { put("script", "mobile: $command"); put("args", buildJsonArray { add(args) }) }) }
        // The ones used on every step have a WebDriverAgent endpoint of their own.
        return when (command) {
            "activeAppInfo" -> hot("GET", "", wdaPath = "/wda/activeAppInfo", sessionless = true, viaAppium = viaAppium)
            "pressButton" -> hot("POST", "", args, wdaPath = "/wda/pressButton", viaAppium = viaAppium)
            "hideKeyboard" -> hot("POST", "", args, wdaPath = "/wda/keyboard/dismiss", viaAppium = viaAppium)
            "terminateApp" -> hot("POST", "", args, wdaPath = "/wda/apps/terminate", viaAppium = viaAppium)
            else -> viaAppium()
        }
    }

    /** The element's W3C id, or null when nothing matches. */
    fun find(using: String, value: String): String? = try {
        // Appium's «-ios class chain» is WebDriverAgent's «class chain».
        val v = hot("POST", "/element", buildJsonObject { put("using", using.removePrefix("-ios ")); put("value", value) },
            viaAppium = { request("POST", s("/element"), buildJsonObject { put("using", using); put("value", value) }) }) as JsonObject
        (v.values.firstOrNull() as? JsonPrimitive)?.content
    } catch (e: WebDriverException) {
        if (e.error == "no such element") null else throw e
    }

    fun activeElement(): String? = runCatching { ((hot("GET", "/element/active") as JsonObject).values.firstOrNull() as? JsonPrimitive)?.content }.getOrNull()

    /** An element's attribute (`name`, `label`, `value`…), or null. */
    fun attribute(element: String, name: String): String? =
        runCatching { (hot("GET", "/element/$element/attribute/$name", timeoutMs = 10_000) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }.getOrNull()

    fun clear(element: String) { hot("POST", "/element/$element/clear") }
    fun setValue(element: String, text: String) { hot("POST", "/element/$element/value", buildJsonObject { put("text", text) }) }
    fun click(element: String) { hot("POST", "/element/$element/click") }
}
