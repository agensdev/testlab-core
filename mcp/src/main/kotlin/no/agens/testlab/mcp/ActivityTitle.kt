package no.agens.testlab.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a tool call did, in words a person watching the phone follows: «Tap "Log in"», «Autopilot
 * "Open settings": tapped "Settings" (92 %)» rather than `handsets_tap_xy x=540 y=1200`. Null for
 * a tool without one (the feed then shows its name and arguments). Already redacted inputs only.
 */
object ActivityTitle {
    fun of(tool: String, args: JsonObject, result: CallToolResult?): String? {
        fun s(k: String) = (args[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        fun q(v: String?, max: Int = 50) = v?.let { "«" + (if (it.length > max) it.take(max - 1) + "…" else it) + "»" }
        val out = result?.content?.filterIsInstance<TextContent>()?.joinToString("\n") { it.text }
        val json = out?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        fun j(k: String) = (json?.get(k) as? JsonPrimitive)?.content
        fun pct(k: String) = j(k)?.toDoubleOrNull()?.let { " (${(it * 100).toInt()} %)" }.orEmpty()
        val chose = (json?.get("action") as? JsonObject)?.let { (it["desc"] as? JsonPrimitive)?.content }
        return when (tool) {
            "handsets_tap" -> "Tap ${q(s("selector_or_text"))}"
            "handsets_tap_xy" -> out?.substringAfter("tapped ", "")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }?.let { "Tap ${q(it)}" } ?: "Tap at ${s("x")},${s("y")}"
            "handsets_show_screen" -> "Show the phone live"
            "lab_report_start" -> "Start the report ${q(s("title"))}"
            "lab_report_write" -> "Write the report"
            "lab_session_start" -> "Start recording"
            "handsets_use_device" -> "Use ${s("serial") ?: "the phone"}"
            "handsets_swipe" -> "Swipe ${s("direction") ?: ""}".trim()
            "handsets_type_text" -> "Type ${q(s("text"), 40)}"
            "handsets_type_secret" -> "Type the stored ${s("ref")}"
            "handsets_fill" -> "Fill ${q(s("selector"))}" + (s("cred")?.let { " with the stored $it" } ?: s("value")?.let { " with ${q(it, 30)}" } ?: "")
            "handsets_submit" -> "Submit"
            "handsets_hide_keyboard" -> "Hide the keyboard"
            "handsets_navigate" -> "Press ${s("target")}"
            "handsets_key" -> "Press ${s("key") ?: "keycode " + s("code")}" + (s("count")?.toIntOrNull()?.takeIf { it > 1 }?.let { " ×$it" } ?: "")
            "handsets_wait_for" -> "Wait for ${q(s("selector_or_text"))}"
            "handsets_open_app" -> "Open ${s("package")}"
            "handsets_close_app" -> "Close ${s("package")}"
            "handsets_go" -> "Go to ${s("destination")}" + (s("subpage")?.let { " › $it" } ?: "")
            "handsets_open_url" -> "Open ${s("url")}"
            "handsets_screenshot" -> "Look at the screen"
            "handsets_ui_dump" -> "Read the screen"
            "handsets_login" -> "Log in" + (s("account")?.let { " as $it" } ?: "")
            "handsets_unlock_device" -> "Unlock the phone"
            "handsets_autopilot_pick" -> "Autopilot looks for ${q(s("goal"))}" + (chose?.let { ": would ${it.replaceFirstChar(Char::lowercase)}${pct("confidence")}" } ?: "")
            "handsets_autopilot_step" -> "Autopilot ${q(s("goal"))}: " + when (j("status")) {
                "goal_done" -> "already there"
                "acted" -> (chose?.replaceFirstChar(Char::lowercase) ?: "acted") + pct("confidence")
                "needs_decision" -> "not sure, asks"
                "blocked" -> "blocked"
                else -> j("status") ?: "…"
            }
            "handsets_autopilot_do" -> "Autopilot ${q(s("goal"))}: " + ((json?.get("steps") as? JsonArray)?.size?.let { "$it step(s), " } ?: "") + (j("status")?.replace('_', ' ') ?: "…")
            "handsets_autopilot_plan", "handsets_autopilot_plan_file" -> "Plan" + (s("name")?.let { " ${q(it)}" } ?: "") + ": " + (j("status")?.replace('_', ' ') ?: "…")
            "handsets_talkback" -> "TalkBack ${s("action") ?: "status"}"
            "handsets_talkback_touch" -> "TalkBack: touch ${q(s("selector_or_text")) ?: "${s("x")},${s("y")}"}"
            "handsets_talkback_activate" -> "TalkBack: double-tap the focused element"
            "handsets_talkback_swipe" -> "TalkBack: ${s("direction") ?: "next"} element"
            "handsets_font_scale" -> "Font size ${s("action") ?: "status"}" + (if (s("action") == "set") " ${s("scale")}" else "")
            "handsets_install_branch_build" -> "Install the build of ${s("branch_or_pr")}"
            "handsets_install_build" -> "Install release ${s("tag")}"
            "lab_report_screenshot" -> "Screenshot for the report: ${q(s("caption"))}"
            "lab_session_note" -> "Note: ${s("text")}"
            else -> null
        }
    }
}
