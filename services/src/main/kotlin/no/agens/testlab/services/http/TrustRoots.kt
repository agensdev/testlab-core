package no.agens.testlab.services.http

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import no.agens.testlab.device.HostOs
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The certificates HTTPS trusts: the JDK's own roots plus the operating system's. On a company
 * laptop with TLS inspection, the proxy's root is installed in the OS store (by MDM or IT) but
 * not in the JDK bundled with the app, so without this every call to GitHub, Jira and TypeSafe
 * fails with "PKIX path building failed". Extra roots can be added as PEM files in
 * `<data>/trust/` or with `TESTLAB_CA_FILE` (path list).
 */
object TrustRoots {
    @Volatile var extraDir: File? = null

    val manager: X509TrustManager by lazy { build() }

    /** Where the trusted roots came from, for diagnostics. */
    @Volatile var sources: List<String> = emptyList()
        private set

    private fun managerOf(ks: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()

    fun build(os: HostOs = HostOs.current(), env: Map<String, String> = System.getenv()): X509TrustManager {
        val found = mutableListOf("JDK")
        val managers = mutableListOf(managerOf(null))
        val extra = mutableListOf<X509Certificate>()
        fun pem(label: String, text: String) {
            val certs = parsePem(text)
            if (certs.isNotEmpty()) { extra += certs; found += "$label (${certs.size})" }
        }
        when (os) {
            HostOs.WINDOWS -> runCatching {
                KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }.also { managers += managerOf(it); found += "Windows certificate store" }
            }
            HostOs.MAC -> runCatching {
                // JDK 21's KeychainStore sees the login keychain only; MDM puts company roots in the System keychain.
                val p = ProcessBuilder("/usr/bin/security", "find-certificate", "-a", "-p", "/Library/Keychains/System.keychain", "/System/Library/Keychains/SystemRootCertificates.keychain")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(10, TimeUnit.SECONDS)
                pem("macOS System keychain", out)
            }
            HostOs.LINUX -> LINUX_BUNDLES.map(::File).firstOrNull { it.isFile }?.let { f -> runCatching { pem(f.path, f.readText()) } }
        }
        val files = no.agens.testlab.services.env("CA_FILE", env)?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map(::File).orEmpty() +
            extraDir?.listFiles { f -> f.name.endsWith(".pem") || f.name.endsWith(".crt") }.orEmpty()
        for (f in files) runCatching { pem(f.path, f.readText()) }
        if (extra.isNotEmpty()) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            extra.forEachIndexed { i, c -> ks.setCertificateEntry("extra-$i", c) }
            managers += managerOf(ks)
        }
        sources = found
        return Composite(managers)
    }

    fun parsePem(text: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(text).mapNotNull { m ->
            runCatching { cf.generateCertificate(ByteArrayInputStream(m.value.toByteArray())) as X509Certificate }.getOrNull()
        }.toList()
    }

    /** Trusted when any of the stores trusts the chain. */
    class Composite(private val managers: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = first { it.checkClientTrusted(chain, authType) }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = first { it.checkServerTrusted(chain, authType) }
        override fun getAcceptedIssuers(): Array<X509Certificate> = managers.flatMap { it.acceptedIssuers.toList() }.distinctBy { it.subjectX500Principal to it.serialNumber }.toTypedArray()

        private fun first(check: (X509TrustManager) -> Unit) {
            var last: CertificateException? = null
            for (m in managers) {
                try { check(m); return } catch (e: CertificateException) { last = e }
            }
            throw last ?: CertificateException("no trust store")
        }
    }

    private val LINUX_BUNDLES = listOf("/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt", "/etc/ssl/ca-bundle.pem", "/etc/ssl/cert.pem")
}

/** Every Ktor CIO client of the app trusts the OS roots too (see [TrustRoots]). */
fun HttpClientConfig<CIOEngineConfig>.osTrust() {
    engine { https { trustManager = TrustRoots.manager } }
}
