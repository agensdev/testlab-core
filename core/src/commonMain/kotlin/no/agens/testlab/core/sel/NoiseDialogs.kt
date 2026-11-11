package no.agens.testlab.core.sel

import kotlinx.serialization.json.JsonElement
import no.agens.testlab.core.profile.Profile

/** Finds a profile's noise dialog (Profile.dismiss) on screen and the button that dismisses it. */
object NoiseDialogs {
    data class Hit(val rule: Profile.Dismiss, val button: UiNode)

    fun find(dump: JsonElement, rules: List<Profile.Dismiss>): Hit? {
        if (rules.isEmpty()) return null
        val roots = UiNode.roots(dump)
        val nodes = roots.flatMap { it.walk().toList() }
        val pkgs = roots.map { it.str("pkg") }.toSet()
        for (r in rules) {
            if (r.packages.isNotEmpty() && pkgs.none { it in r.packages }) continue
            val shown = nodes.any { n -> r.whenShown.any { w -> n.text.contains(w, ignoreCase = true) || n.desc.contains(w, ignoreCase = true) } }
            if (!shown) continue
            for (label in r.tap) {
                val b = nodes.firstOrNull { n -> (n.text.equals(label, ignoreCase = true) || n.desc.equals(label, ignoreCase = true)) && n.bounds?.hasArea == true }
                if (b != null) return Hit(r, b)
            }
        }
        return null
    }
}
