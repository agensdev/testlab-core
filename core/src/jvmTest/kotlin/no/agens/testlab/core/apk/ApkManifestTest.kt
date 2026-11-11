package no.agens.testlab.core.apk

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The fixture APKs are built by tools/testapp/build-fixture.sh with the SDK's aapt2. */
class ApkManifestTest {
    private fun manifest(name: String): ManifestInfo = ZipFile(File("../tools/testapp/$name")).use { z ->
        ApkManifest.parse(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
    }

    @Test
    fun readsPackageVersionAndSdks() {
        val links = listOf("https://www.example.com", "exampleshop://open")
        assertEquals(ManifestInfo("no.agens.testlab.fixture", 1, "1.0-fixture", 24, 36, links), manifest("fixture-v1.apk"))
        assertEquals(ManifestInfo("no.agens.testlab.fixture", 2, "2.0-fixture", 24, 36, links), manifest("fixture-v2.apk"))
        assertEquals(3L, manifest("fixture-otherkey.apk").versionCode)
    }

    @Test
    fun rejectsWhatIsNotBinaryXml() {
        assertFailsWith<ApkFormatError> { ApkManifest.parse("<manifest package=\"x\"/>".encodeToByteArray()) }
        assertFailsWith<ApkFormatError> { ApkManifest.parse(byteArrayOf(3, 0, 8, 0, 100, 0, 0, 0, 1, 0)) }
    }
}
