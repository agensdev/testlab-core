package no.agens.testlab.services.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserTest {
    @Test
    fun theDefaultBrowserIsTheHttpsHandler() {
        // As `plutil -convert json` prints Launch Services' preferences on a Mac with Chrome as the default browser.
        val ls = """{"LSHandlers":[
            {"LSHandlerContentType":"public.html","LSHandlerRoleAll":"com.apple.safari"},
            {"LSHandlerURLScheme":"http","LSHandlerRoleAll":"com.google.chrome"},
            {"LSHandlerURLScheme":"https","LSHandlerRoleAll":"com.google.chrome"},
            {"LSHandlerURLScheme":"mailto","LSHandlerRoleAll":"com.apple.mail"}]}"""
        assertEquals("com.google.chrome", Browser.handlerFor(ls), "the https handler, not the app that owns .html files")
        assertEquals("org.mozilla.firefox", Browser.handlerFor("""{"LSHandlers":[{"LSHandlerURLScheme":"http","LSHandlerRoleAll":"org.mozilla.firefox"}]}"""))
        assertEquals(null, Browser.handlerFor("""{"LSHandlers":[]}"""), "none chosen: Safari, which plain `open` uses")
        assertEquals(null, Browser.handlerFor("not json"))
    }
}
