package no.agens.testlab.services.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import no.agens.testlab.core.jev.HttpPost
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Jev looking at a picture of the screen (TypeSafe System One, «Box» on an image): where a thing is on
 * screens without an accessibility tree (games, canvas, Unity). One request with two questions:
 * a Box (always answers with a box) and a noul «is it there at all», which decides found.
 * Images and Box are an early TypeSafe feature (the Danger-Zone header), so their errors are explained.
 */
class JevFind(
    private val http: HttpPost,
    private val apiKey: () -> String?,
    private val url: String = "https://api.typesafe.ai/v1/systemone",
    private val model: String = "jev-latest",
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    data class Result(
        val found: Boolean, val present: Double, val confidence: Double, val box: Box,
        /** The box's centre in the tap pixels of the screen ([screenW] × [screenH]). */
        val x: Int, val y: Int,
        val image: Pair<Int, Int>, val latencyMs: Long, val usage: JsonObject?,
    ) {
        fun json() = buildJsonObject {
            put("found", found); put("present", round(present)); put("confidence", round(confidence))
            putJsonObject("box") { put("left", round(box.left)); put("top", round(box.top)); put("right", round(box.right)); put("bottom", round(box.bottom)) }
            put("x", x); put("y", y)
        }
    }

    /**
     * Where [goal] is in [jpeg] (the upright screen). [screenW]×[screenH] is the tap pixel space; when
     * the picture is landscape and the size portrait (or the other way), the size is turned to match.
     */
    suspend fun find(goal: String, jpeg: ByteArray, screenW: Int, screenH: Int): Result {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("no TypeSafe API key: `testlab secrets set typesafe_api_key` (the same key Jev uses, jev.key)")
        val (img, w, h) = fit(jpeg)
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("state") { put("screen", "s1") }
            putJsonObject("assets") { putJsonObject("s1") { put("type", "image"); put("format", "jpg"); put("data_base64", Base64.getEncoder().encodeToString(img)) } }
            putJsonObject("questions") {
                putJsonObject("target") { put("type", "box"); put("instructions", "$goal in `screen`") }
                putJsonObject("present") { put("type", "noul"); put("instructions", "`screen` shows $goal") }
            }
        }.toString()
        val t0 = nowMs()
        val reply = http.post(url, mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $key", "TypeSafe-Danger-Zone-Images" to "true"), body, 30_000, 5_000)
        val latency = nowMs() - t0
        if (reply.status != 200) throw IllegalStateException(explain(reply.status, reply.text))
        val data = runCatching { Json.parseToJsonElement(reply.text) as JsonObject }.getOrNull()
            ?: throw IllegalStateException("TypeSafe's answer isn't JSON: ${reply.text.take(200)}")
        val answers = data["answers"] as? JsonObject ?: throw IllegalStateException("TypeSafe's answer has no answers: ${reply.text.take(300)}")
        val target = answers["target"] as? JsonObject ?: throw IllegalStateException("no box in TypeSafe's answer: ${reply.text.take(300)}")
        val b = target["box"] as? JsonObject ?: throw IllegalStateException("no box in TypeSafe's answer: ${reply.text.take(300)}")
        fun d(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull()
        val box = Box(d(b, "left") ?: 0.0, d(b, "top") ?: 0.0, d(b, "right") ?: 0.0, d(b, "bottom") ?: 0.0)
        val present = d(answers["present"] as? JsonObject, "noul") ?: 0.0
        val (sw, sh) = if ((w > h) != (screenW > screenH)) screenH to screenW else screenW to screenH
        return Result(
            found = present >= 0.5, present = present, confidence = d(target, "confidence") ?: 0.0, box = box,
            x = Math.round((box.left + box.right) / 2 * sw).toInt(), y = Math.round((box.top + box.bottom) / 2 * sh).toInt(),
            image = w to h, latencyMs = latency, usage = data["usage"] as? JsonObject,
        )
    }

    companion object {
        /** TypeSafe rejects (does not resize) images outside 280–1568 px per side. */
        const val MIN_SIDE = 280
        const val MAX_SIDE = 1568
        const val LONG_SIDE = 1400

        private fun round(v: Double) = Math.round(v * 1000) / 1000.0

        /** The JPEG with its long side at most [LONG_SIDE], and its size; refused when a side is under [MIN_SIDE]. */
        fun fit(jpeg: ByteArray): Triple<ByteArray, Int, Int> {
            val src = ImageIO.read(jpeg.inputStream()) ?: throw IllegalStateException("the screenshot isn't an image")
            val long = maxOf(src.width, src.height)
            val img = if (long <= LONG_SIDE) src else {
                val f = LONG_SIDE.toDouble() / long
                val w = maxOf(1, Math.round(src.width * f).toInt()); val h = maxOf(1, Math.round(src.height * f).toInt())
                BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
                    val g = out.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(src, 0, 0, w, h, null); g.dispose()
                }
            }
            if (minOf(img.width, img.height) < MIN_SIDE) throw IllegalStateException("the screenshot is ${img.width}×${img.height}: TypeSafe needs at least $MIN_SIDE px on each side")
            if (img === src) return Triple(jpeg, img.width, img.height)
            val out = ByteArrayOutputStream()
            ImageIO.write(img, "jpg", out)
            return Triple(out.toByteArray(), img.width, img.height)
        }

        /** A 4xx/5xx in words: images and Box are an early TypeSafe feature, so say what to check. */
        fun explain(status: Int, text: String): String {
            val detail = text.take(300).ifBlank { "no detail" }
            return when (status) {
                401, 403 -> "TypeSafe refused the key ($status): check it (`testlab secrets set typesafe_api_key`), and that the account has images/Box (an early TypeSafe feature, the Danger-Zone header): $detail"
                400, 413, 415, 422 -> "TypeSafe didn't take the picture question ($status): images must be 280–1568 px per side, JPEG; Box is an early TypeSafe feature and its format may have changed (docs.typesafe.ai, primitives/box): $detail"
                429 -> "TypeSafe is rate-limiting ($status): try again in a moment: $detail"
                else -> "TypeSafe answered $status: $detail"
            }
        }
    }
}
