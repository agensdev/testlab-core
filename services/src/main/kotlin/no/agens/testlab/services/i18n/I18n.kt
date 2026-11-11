package no.agens.testlab.services.i18n

/**
 * The texts people read (checklists, device help), in English: `{0}`, `{1}`… are placeholders
 * and `{app}` is the app's name, so a branded build names itself. Texts for Claude (tool
 * descriptions and results) are written out in full.
 */
object I18n {
    fun tr(en: String, vararg args: Any?): String {
        var out = en.replace("{app}", no.agens.testlab.AppIdentity.NAME)
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a?.toString() ?: "") }
        return out
    }
}

/** Shorthand for [I18n.tr]. */
fun tr(en: String, vararg args: Any?): String = I18n.tr(en, *args)
