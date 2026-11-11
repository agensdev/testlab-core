package no.agens.testlab.services.http

import no.agens.testlab.device.HostOs
import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrustRootsTest {
    private val pem = javaClass.getResource("/tls/corp-root.pem")!!.readText()

    // HostOs.WINDOWS off Windows: the OS store is unavailable, so only the JDK and the extras count.
    @Test
    fun aCompanyRootFromTheTrustFolderIsTrustedNextToTheJdks() {
        val corp = TrustRoots.parsePem("junk\n$pem\nmore junk").single()
        val dir = Files.createTempDirectory("tl-trust").toFile()
        try {
            TrustRoots.extraDir = dir
            assertFailsWith<CertificateException> { TrustRoots.build(HostOs.WINDOWS, emptyMap()).checkServerTrusted(arrayOf(corp), "ECDHE_ECDSA") }
            File(dir, "corp.pem").writeText(pem)
            TrustRoots.build(HostOs.WINDOWS, emptyMap()).checkServerTrusted(arrayOf(corp), "ECDHE_ECDSA")
            assertTrue(TrustRoots.sources.any { it.endsWith("corp.pem (1)") }, TrustRoots.sources.toString())
            assertEquals("JDK", TrustRoots.sources.first())
        } finally {
            TrustRoots.extraDir = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun theEnvironmentCanNameAFile() {
        val f = Files.createTempFile("corp", ".pem").toFile().apply { writeText(pem) }
        try {
            TrustRoots.build(HostOs.WINDOWS, mapOf("NTL_CA_FILE" to f.path)).checkServerTrusted(arrayOf(TrustRoots.parsePem(pem).single()), "ECDHE_ECDSA")
        } finally { f.delete() }
    }

    @Test
    fun linuxReadsTheSystemBundle() {
        TrustRoots.build(HostOs.LINUX, emptyMap())
        if (File("/etc/ssl/certs/ca-certificates.crt").isFile) assertTrue(TrustRoots.sources.any { it.startsWith("/etc/ssl/certs/ca-certificates.crt") }, TrustRoots.sources.toString())
    }
}
