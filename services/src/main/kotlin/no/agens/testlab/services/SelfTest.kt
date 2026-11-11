package no.agens.testlab.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.AppIdentity
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext

/**
 * `--selftest[=file]`: checks that a packaged app has the JDK modules it needs (TLS,
 * elliptic curves, AES-GCM) without opening a window. CI runs it on every packaged build,
 * because a module missing from the jlink'd runtime only shows up at runtime.
 */
object SelfTest {
    @Serializable
    data class Check(val name: String, val ok: Boolean, val detail: String = "")

    @Serializable
    data class Result(val app: String, val version: String, val java: String, val os: String, val ok: Boolean, val checks: List<Check>)

    private val json = Json { prettyPrint = true }

    fun run(): Result {
        val checks = listOf(
            check("tls13") { SSLContext.getInstance("TLSv1.3").also { it.init(null, null, null) }.protocol },
            check("ec-keys") { KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.algorithm },
            check("aes-gcm") {
                val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12))) }
                "${c.doFinal("x".toByteArray()).size} bytes"
            },
            check("app-dirs") { AppDirs.forHost().data.path },
            check("temp-dir") { File.createTempFile("tl-selftest", ".tmp").also { it.deleteOnExit() }.parent },
        )
        return Result(
            app = AppIdentity.NAME,
            version = AppIdentity.VERSION,
            java = System.getProperty("java.runtime.version").orEmpty(),
            os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
            ok = checks.all { it.ok },
            checks = checks,
        )
    }

    /** Runs the checks, writes the JSON to [outFile] when given, and returns the exit code. */
    fun runAndReport(outFile: File?): Int {
        val r = run()
        val text = json.encodeToString(Result.serializer(), r)
        if (outFile != null) outFile.writeText(text) else println(text)
        return if (r.ok) 0 else 1
    }

    fun isRequested(args: Array<String>): Boolean = args.any { it == "--selftest" || it.startsWith("--selftest=") }

    /** The file from `--selftest=path`, or null for plain `--selftest` (print to stdout). */
    fun outputFile(args: Array<String>): File? =
        args.firstOrNull { it.startsWith("--selftest=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun check(name: String, block: () -> Any?): Check = try {
        Check(name, true, block().toString())
    } catch (e: Throwable) {
        Check(name, false, "${e::class.simpleName}: ${e.message}")
    }
}
