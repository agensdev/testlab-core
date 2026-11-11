package no.agens.testlab.services.i18n

import no.agens.testlab.AppIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {
    @Test
    fun placeholdersAndTheAppName() {
        assertEquals("Installs go to emulator-5554", tr("Installs go to {0}", "emulator-5554"))
        assertEquals("${AppIdentity.NAME} on 2 of 3", tr("{app} on {0} of {1}", 2, 3))
        assertEquals("no args {0}", tr("no args {0}"))
    }
}
