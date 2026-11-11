package no.agens.testlab.services.builds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.core.apk.ApkManifest
import no.agens.testlab.core.apk.ManifestInfo
import no.agens.testlab.services.github.Artifact
import no.agens.testlab.services.github.GitHub
import no.agens.testlab.services.github.GitHubError
import no.agens.testlab.services.github.ReleaseAsset
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Downloaded builds in the OS cache folder, one folder per artifact or release asset:
 * `builds/a<artifact id>/<name>.apk` and `builds/r<asset id>/<name>.apk`. A build is unpacked
 * into a temporary folder and renamed into place, so a half-finished download is never mistaken
 * for a build. Installing again uses the file on disk.
 */
class BuildFiles(val dir: File) {
    fun cachedArtifact(id: Long): File? = buildIn(File(dir, "a$id"))
    fun cachedAsset(id: Long): File? = buildIn(File(dir, "r$id"))

    private fun apkIn(d: File): File? = d.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.firstOrNull()

    /** An APK, an iOS device build's `.ipa`, or an iOS Simulator `.app` folder. */
    private fun buildIn(d: File): File? = apkIn(d) ?: IosApp.ipaIn(d) ?: IosApp.appIn(d).takeIf { d.isDirectory }

    /** Downloads and unpacks [a]: an APK, or with [ios] the `.app` bundle of a simulator build. */
    suspend fun artifact(gh: GitHub, a: Artifact, ios: Boolean = false, progress: (Long, Long?) -> Unit = { _, _ -> }): File {
        cachedArtifact(a.id)?.let { return it }
        if (a.expired) throw GitHubError(410, "The build ${a.name} (artifact ${a.id}) has expired: GitHub keeps builds for 30 days. Start a new one")
        val zip = File(dir, "a${a.id}.zip")
        gh.downloadArtifact(a.id, zip) { done, total -> progress(done, total ?: a.bytes.takeIf { it > 0 }) }
        return withContext(Dispatchers.IO) { (if (ios) unpackApp(zip, File(dir, "a${a.id}"), a.name) else unpack(zip, File(dir, "a${a.id}"))).also { zip.delete() } }
    }

    /** A simulator build's `.app` (or a device build's `.ipa`) from [zip], moved into [target] atomically. */
    private fun unpackApp(zip: File, target: File, name: String): File {
        val tmp = File(dir, target.name + ".tmp").apply { deleteRecursively() }
        try {
            IosApp.unpack(zip, tmp, name)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw GitHubError(0, "Could not move the build into ${target.path}")
        } finally {
            tmp.deleteRecursively()
        }
        return IosApp.ipaIn(target) ?: IosApp.appIn(target)!!
    }

    suspend fun asset(gh: GitHub, a: ReleaseAsset, progress: (Long, Long?) -> Unit = { _, _ -> }): File {
        cachedAsset(a.id)?.let { return it }
        val target = File(dir, "r${a.id}")
        val download = File(dir, "r${a.id}-" + safeName(a.name))
        gh.downloadAsset(a.apiUrl, download) { done, total -> progress(done, total ?: a.bytes.takeIf { it > 0 }) }
        return withContext(Dispatchers.IO) {
            if (a.name.endsWith(".zip")) unpack(download, target).also { download.delete() }
            else place(download, target, safeName(a.name))
        }
    }

    /** The first `.apk` in [zip], moved into [target] atomically. */
    private fun unpack(zip: File, target: File): File {
        val tmp = File(dir, target.name + ".tmp").apply { deleteRecursively(); mkdirs() }
        try {
            ZipFile(zip).use { z ->
                val entry = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".apk") }
                    ?: throw GitHubError(0, "The download has no .apk inside (${zip.name})")
                // Only the file name: an entry like "../x.apk" must not escape the cache folder.
                val out = File(tmp, safeName(entry.name.substringAfterLast('/')))
                z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            target.deleteRecursively()
            if (!tmp.renameTo(target)) throw GitHubError(0, "Could not move the build into ${target.path}")
        } finally {
            tmp.deleteRecursively()
        }
        return apkIn(target)!!
    }

    private fun place(file: File, target: File, name: String): File {
        val tmp = File(dir, target.name + ".tmp").apply { deleteRecursively(); mkdirs() }
        file.renameTo(File(tmp, name)) || run { file.copyTo(File(tmp, name), overwrite = true); file.delete() }
        target.deleteRecursively()
        tmp.renameTo(target)
        return apkIn(target)!!
    }

    /** Space used by downloaded builds. */
    fun bytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() { dir.listFiles()?.forEach { it.deleteRecursively() } }

    /** Removes downloads whose artifact GitHub no longer has (it deletes them after 30 days). */
    fun prune(keepArtifactIds: Set<Long>) {
        dir.listFiles { f -> f.isDirectory && f.name.startsWith("a") }?.forEach { d ->
            val id = d.name.removePrefix("a").toLongOrNull() ?: return@forEach
            if (id !in keepArtifactIds) d.deleteRecursively()
        }
    }

    companion object {
        fun safeName(n: String): String = n.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "build.apk" }

        /** Package (bundle id) and version of an APK or an iOS `.app`. */
        fun manifest(apk: File): ManifestInfo = if (apk.isDirectory) IosApp.manifest(apk) else if (apk.name.endsWith(".ipa")) IosApp.ipaManifest(apk) else ZipFile(apk).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: throw no.agens.testlab.core.apk.ApkFormatError("${apk.name} has no AndroidManifest.xml (not an APK?)")
            ApkManifest.parse(z.getInputStream(e).readBytes())
        }

        /** The file's SHA-256; for an iOS `.app` folder, its Info.plist and executable's. */
        fun sha256(f: File): String {
            if (f.isDirectory) return IosApp.fingerprint(f)
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
