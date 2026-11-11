package no.agens.testlab.services.builds

import no.agens.testlab.AppIdentity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.core.apk.ManifestInfo
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.shQuote
import java.io.File
import java.time.Instant

/** Why `adb install` failed, in words a tester can act on. */
enum class InstallProblem(val code: String, val advice: String) {
    SIGNATURE(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
        "The installed copy is signed with another key (a local Android Studio build, or Play). Uninstalling it deletes its data, login included; then install again",
    ),
    USB_INSTALL_OFF(
        "INSTALL_FAILED_USER_RESTRICTED",
        "The phone blocks installs over USB. Xiaomi: Developer options → turn on «Install via USB» (needs a SIM and a Mi account). OPPO, OnePlus, Realme: turn off «Disable permission monitoring»/turn on «Install via USB». Vivo: allow «Install via USB»",
    ),
    REJECTED_ON_PHONE("INSTALL_FAILED_ABORTED", "The install was not confirmed on the phone. Look at the phone: some ask to allow each install over USB, then try again"),
    NO_SPACE("INSTALL_FAILED_INSUFFICIENT_STORAGE", "The phone is out of storage: free some space and try again"),
    ABI("INSTALL_FAILED_NO_MATCHING_ABIS", "The build has no native code for this phone's CPU (an x86 emulator for an ARM-only build?)"),
    OLD_ANDROID("INSTALL_FAILED_OLDER_SDK", "The build needs a newer Android version than this phone has"),
    DOWNGRADE("INSTALL_FAILED_VERSION_DOWNGRADE", "The installed copy is newer and the phone refuses a downgrade: uninstall it first"),
    VERIFICATION("INSTALL_FAILED_VERIFICATION_FAILURE", "Play Protect or the phone's verifier blocked it: allow the app in Play Protect, or turn off «Verify apps over USB» in Developer options"),
    TEST_ONLY("INSTALL_FAILED_TEST_ONLY", "The build is marked test-only"),
    NOT_AN_APK("INSTALL_PARSE_FAILED", "The file is not a valid, signed APK"),
    ;

    companion object {
        fun of(output: String): InstallProblem? = entries.firstOrNull { it.code in output }
    }
}

data class InstallResult(val ok: Boolean, val output: String, val problem: InstallProblem?) {
    val advice: String? get() = problem?.advice ?: if (!ok) "the install failed: ${output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "no output"}" else null
}

/** What is on which device, as far as ${AppIdentity.NAME} installed it. All feature builds share one package, so the phone can't tell. */
@Serializable
data class InstalledBuild(
    val serial: String,
    val packageName: String,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val source: String,
    val branch: String? = null,
    val headSha: String? = null,
    val artifactId: Long? = null,
    val tag: String? = null,
    val apkSha256: String,
    val installedAt: String,
)

class InstalledBuilds(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    fun all(): List<InstalledBuild> = try {
        if (file.isFile) json.decodeFromString<List<InstalledBuild>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun on(serial: String): List<InstalledBuild> = all().filter { it.serial == serial }

    @Synchronized
    fun record(b: InstalledBuild) {
        val next = all().filterNot { it.serial == b.serial && it.packageName == b.packageName } + b
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(next))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    @Synchronized
    fun forget(serial: String, pkg: String) {
        val all = all()
        val next = all.filterNot { it.serial == serial && it.packageName == pkg }
        if (next.size != all.size) file.writeText(json.encodeToString(next))
    }
}

/** `adb install -r -d` (replace, allow downgrade: feature builds of an older branch have older version codes). */
class Installer(private val rt: LabRuntime) {
    suspend fun install(serial: String, apk: File): InstallResult = withContext(Dispatchers.IO) {
        val r = rt.adb().install(serial, apk)
        val out = (r.stdout + "\n" + r.stderr).trim()
        val ok = r.ok && out.lines().any { it.trim() == "Success" }
        InstallResult(ok, out.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n"), if (ok) null else InstallProblem.of(out) ?: if ("INSTALL_PARSE_FAILED" in out) InstallProblem.NOT_AN_APK else null)
    }

    /** Whether [pkg] is installed on [serial] now (the record alone can be stale: the user may have uninstalled it). */
    suspend fun isInstalled(serial: String, pkg: String): Boolean = withContext(Dispatchers.IO) {
        rt.adb().shell(serial, "pm path ${shQuote(pkg)}").stdout.lines().any { it.startsWith("package:") }
    }

    companion object {
        fun record(serial: String, apk: File, info: ManifestInfo, source: String, branch: String? = null, headSha: String? = null, artifactId: Long? = null, tag: String? = null) =
            InstalledBuild(serial, info.packageName, info.versionCode, info.versionName, source, branch, headSha, artifactId, tag, BuildFiles.sha256(apk), Instant.now().toString())
    }
}
