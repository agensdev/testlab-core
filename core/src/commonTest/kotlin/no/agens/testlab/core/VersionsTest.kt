package no.agens.testlab.core

import no.agens.testlab.AppIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionsTest {
    @Test
    fun macVersionNeverStartsWithZero() {
        assertEquals("1.1.0", macPackageVersion("0.1.0"))
        assertEquals("1.0.7", macPackageVersion("0.0.7"))
        assertEquals("2.3.4", macPackageVersion("2.3.4"))
        assertEquals("1.2.0", macPackageVersion("0.2"))
    }

    @Test
    fun identityIsGenerated() {
        assertTrue(AppIdentity.ID.isNotBlank())
        assertEquals(8378, AppIdentity.MCP_DEFAULT_PORT)
        assertTrue(versionLine("test").startsWith(AppIdentity.NAME))
    }
}
