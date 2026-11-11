package no.agens.testlab.services.service

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNoticeTest {
    @Test
    fun appleScriptStringsKeepQuotesAndBackslashesInside() {
        assertEquals("\"Post «\\\"Devtest\\\"» on PROJ-1 \\\\ now\"", DesktopNotice.quote("Post «\"Devtest\"» on PROJ-1 \\ now"))
        assertEquals("\"two lines\"", DesktopNotice.quote("two\nlines"))
    }
}
