package no.agens.testlab.core.autopilot

import kotlinx.serialization.json.JsonObject

/** Configuration / API / parse problems on the decision side (not device errors). */
class AutopilotError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

enum class Backend(val id: String) {
    TYPESAFE("typesafe"), OLLAMA("ollama"), OPENAI("openai");

    companion object {
        fun parse(s: String): Backend = entries.firstOrNull { it.id == s }
            ?: throw AutopilotError("unknown backend '$s' (typesafe|ollama|openai)")
    }
}

/**
 * Every knob of jevpick.py with the same defaults, except where a local install needs another
 * one: Ollama defaults to this machine instead of the lab's LAN hosts.
 */
data class AutopilotConfig(
    val backend: Backend = Backend.TYPESAFE,
    val typesafeUrl: String = "https://api.typesafe.ai/v1/systemone",
    val model: String = "jev-latest",
    val timeoutS: Double = 10.0,
    /** Failover list tried in order; entries may carry a model: `url|model`. */
    val ollamaUrls: List<String> = listOf("http://localhost:11434/v1/systemone|nimble:9b"),
    val ollamaModel: String = "nimble:9b",
    val ollamaTimeoutS: Double = 120.0,
    val ollamaConnectTimeoutS: Double = 3.0,
    val ollamaBackoffS: Double = 60.0,
    /** OpenAI's Decisions API (`POST /v1/decisions`): the same choice + yes/no questions, hosted. */
    val openaiUrl: String = "https://api.openai.com/v1/decisions",
    val openaiModel: String = "gpt-6-luna",
    val openaiTimeoutS: Double = 10.0,
    val stubConfidence: Double = 0.8,
    val maxTexts: Int = 40,
    val deadlineS: Double = 90.0,
    val stateCandidates: Boolean = false,
    val doneThreshold: Double = 0.6,
    val doneVeto: Double = 0.25,
    val fallbackMin: Double = 0.2,
    val harmlessMin: Double = 0.3,
    /** App vocabulary for the model (`Label=meaning; …`), from the profile. */
    val glossary: String = "",
    /** The profile's wording for the questions; null = the core's English defaults. */
    val pickExample: String? = null,
    val doneExample: String? = null,
    val labelLanguage: String? = null,
    val pickNote: String? = null,
    /** Extra risky / leave words (regex alternatives) from the profile. */
    val riskyWords: List<String> = emptyList(),
    val leaveWords: List<String> = emptyList(),
    /** What the questions call the device, and the back action: Android's unless the device is an iPhone. */
    val device: String = "an Android phone",
    val backAction: String = META_BACK_ANDROID,
) {
    /** The meta options with this device's back action. */
    val meta: Map<String, String> get() = if (backAction == META_BACK_ANDROID) META else META + ("back" to backAction)

    val questionInstructions: String get() = questionInstructions(this)
    val doneInstructions: String get() = doneInstructions(this)
    val riskyRe: Regex by lazy { riskyRegex(riskyWords) }
    val leaveRe: Regex by lazy { leaveRegex(leaveWords) }
}

const val OLLAMA_MAX_OPTIONS = 26
const val MAX_CRITERIA = 255
const val TEXT_CUT = 80
const val DESC_CUT = 60
const val HISTORY_TAIL = 5
const val JACCARD_SAME = 0.9
const val LABEL_PARTS = 3
val BACKOFF_S = listOf(0.5, 1.0, 2.0, 4.0)

val META: Map<String, String> = linkedMapOf(
    "scroll_down" to "Scroll the screen down to reveal content further below",
    "scroll_up" to "Scroll the screen up to reveal content further above",
    "back" to META_BACK_ANDROID,
)

const val META_BACK_ANDROID = "Press the Android back button (leave this screen / close a dialog)"
const val META_BACK_IOS = "Go back: the navigation bar's back button or the back swipe (leave this screen / close a dialog)"

val TERMINAL: Map<String, String> = linkedMapOf(
    "goal_done" to "The goal is ALREADY achieved: the screen/section the goal asks for is the one currently open",
    "blocked" to "None of the listed elements would lead toward the goal from this screen",
)

fun doneInstructions(c: AutopilotConfig): String =
    "Is `goal` ALREADY accomplished, i.e. is the screen/section the goal asks for the one currently " +
        "open? Judge from `screen.title` (the page heading - if it names what the goal asks to open, the " +
        "goal is accomplished), `screen.selected` (the selected tab), `screen.visible_texts` (its content) " +
        "and `history` (the last action). A tab or section label merely being visible in a navigation " +
        "bar or menu does NOT mean it is open. Tab names must match exactly" + (c.doneExample?.let { ": $it" } ?: "") +
        ". Answer no if the screen is still where the goal started."

fun questionInstructions(c: AutopilotConfig): String =
    "Choose the single next action on ${c.device} so that `goal` is accomplished. " +
        "Each option is either one tappable element currently on screen (its label is quoted" +
        (c.labelLanguage?.let { "; labels are mostly $it, the goal may be English" } ?: "") + ") or a meta action. Pick the element most likely " +
        "to lead toward the goal. Do NOT require a literal word match: section names, tabs, tiles and " +
        "synonyms count " + (c.pickExample ?: "(e.g. the goal 'Open Settings' is served by a 'More' tab when no 'Settings' element is visible)") +
        ". Pick the option stating the goal " +
        "is ALREADY achieved when `screen.selected` / `screen.visible_texts` show that - do not act again. " +
        "Pick the option stating that none of the elements lead toward the goal when nothing can plausibly " +
        "advance it, or when progress would require typing text, paying, or confirming a purchase. Pick a " +
        "scroll option only if the needed element is probably off-screen. Never repeat an action that `history` says produced no change. " +
        "`screen.selected` names the currently selected tab; a tab label being visible in the bottom " +
        "bar does NOT mean that tab is open - tap it to open it. Tapping a tab or section is free and " +
        "reversible" + (c.pickNote?.let { "; $it" } ?: "") + "." + (if (c.glossary.isNotBlank()) " Glossary: " + c.glossary else "")

/**
 * [questionInstructions] for a step the plan explicitly allows to pay: its "paying / confirming a purchase
 * means blocked" clause contradicts the step (benchmark 8: OpenAI gave the right «Pay» button 0.44-0.48,
 * under the 0.5 floor for risky taps; 1.00 without the clause).
 */
fun riskyAllowedInstructions(text: String): String = text.replace(
    "would require typing text, paying, or confirming a purchase",
    "would require typing text (paying or confirming a purchase IS allowed in this step: it is what the goal asks for)",
)

/** Purchase / destructive words: such taps are dropped unless a step allows risk. The profile adds words in its language. */
fun riskyRegex(extra: List<String> = emptyList()): Regex = Regex("(?iuU)" + (extra + listOf("uninstall", "delete", "pay\\b", "buy", "purchase")).joinToString("|"))
val RISKY_RE = riskyRegex()

/** Elements that leave the current screen are not "harmless" for a low-confidence tap. */
fun leaveRegex(extra: List<String> = emptyList()): Regex = Regex("(?iuU)^(" + (listOf("go back", "back") + extra + listOf("close( sheet)?", "cancel", "home")).joinToString("|") + ")$")
val LEAVE_RE = leaveRegex()

/** Goals phrased as one action resolve as soon as that action changed the screen. */
private val ACTION_GOAL_RE = Regex(
    "(?iuU)^${PyText.S}*(tap|press|click|choose|select|pick|dismiss|skip|accept|confirm|continue|" +
        "close|focus|toggle|check|uncheck|swipe|scroll)\\b",
)

fun isActionGoal(goal: String): Boolean = ACTION_GOAL_RE.containsMatchIn(goal)

/** One parsed UI node (a line of `hs ui --tree` or of the `hs ui` table). */
class Node(
    val verb: String,
    val widget: String,
    var text: String,
    val rid: String,
    val x: Int,
    val y: Int,
    val raw: String = "",
    /** Table: "long", "long scroll"; tree: "click focusable selected" (commas replaced by spaces). */
    val flags: String = "",
    /** contentDescription (tree form only). */
    val desc: String = "",
    /** Indent width (tree form only). */
    val depth: Int = 0,
) {
    val actionable: Boolean get() = verb == "tap"
    val label: String get() = text.ifEmpty { if (rid.isNotEmpty()) "#$rid" else "" }
    override fun toString() = "Node($verb $widget \"$text\" #$rid @$x,$y)"
}

enum class CandidateKind(val id: String) { TAP("tap"), META("meta"), TERMINAL("terminal") }

class Candidate(val key: String, val kind: CandidateKind, val desc: String, val node: Node? = null, val risky: Boolean = false) {
    /** Identity for loop exclusion: (text, rid) of the node, or (key, "") for meta/terminal. */
    val ident: Pair<String, String> get() = node?.let { it.text to it.rid } ?: (key to "")
}

/** The decision model's answer. [done] is the fan-out yes/no "is the goal already accomplished?". */
class Decision(
    val choice: String,
    val confidence: Double,
    /** In the order the API returned them. */
    val probabilities: Map<String, Double>,
    val model: String = "",
    val usage: JsonObject = JsonObject(emptyMap()),
    val latencyMs: Long = 0,
    /** The raw response, plus `_backend` / `_url` added by the client. */
    val raw: JsonObject = JsonObject(emptyMap()),
    val done: Double? = null,
) {
    /** `sorted(probabilities.items(), key=-p)[:n]`: stable, so ties keep the API order. */
    fun top(n: Int = 5): List<Pair<String, Double>> = probabilities.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
}

/** Device size for scaling the pixel constants that jevpick tuned on a 1440×3120 Pixel. */
data class Geometry(val width: Int = REF_WIDTH, val height: Int = REF_HEIGHT) {
    fun sx(px: Int): Int = px * width / REF_WIDTH
    fun sy(px: Int): Int = px * height / REF_HEIGHT

    companion object {
        const val REF_WIDTH = 1440
        const val REF_HEIGHT = 3120
        val REFERENCE = Geometry()
    }
}
