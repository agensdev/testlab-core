package no.agens.testlab.services.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkBackTest {
    @Test
    fun enabledList() {
        assertEquals(emptyList(), TalkBack.enabledList("null"))
        assertEquals(emptyList(), TalkBack.enabledList(""))
        assertEquals(listOf("a/.A", TalkBack.GOOGLE_COMPONENT), TalkBack.enabledList("a/.A:${TalkBack.GOOGLE_COMPONENT}\n"))
        assertTrue(TalkBack.isTalkBack(TalkBack.GOOGLE_COMPONENT))
        assertTrue(TalkBack.isTalkBack(TalkBack.SAMSUNG_COMPONENT))
        assertFalse(TalkBack.isTalkBack("com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService"))
    }
}
