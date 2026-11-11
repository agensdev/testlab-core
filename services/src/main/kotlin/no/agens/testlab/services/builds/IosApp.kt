package no.agens.testlab.services.builds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.core.apk.ManifestInfo
import no.agens.testlab.device.HostOs
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * iOS Simulator builds: a GitHub artifact holding an `.app` bundle. CI often uploads the bundle's
 * *contents* (Info.plist at the zip's root), so the folder is named `<CFBundleExecutable>.app`
 * after unpacking, which `simctl install` needs. A device build is kept as its `.ipa` file, for a
 * real iPhone or this Mac: an `.ipa` inside the artifact, or the artifact itself when it holds
 * `Payload/<name>.app` (an `.ipa` is that zip).
 */
object IosApp {
    class NotASimulatorBuild(message: String) : RuntimeException(message)

    /** Unpacks [zip] into [into] and returns the `.app` folder in it, or the `.ipa` of a device build. */
    fun unpack(zip: File, into: File, name: String = "App"): File {
        val names = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }
        into.mkdirs()
        names.firstOrNull { it.endsWith(".ipa") && !it.endsWith("/") }?.let { entry ->
            val out = File(into, BuildFiles.safeName(entry.substringAfterLast('/')))
            ZipFile(zip).use { z -> z.getInputStream(z.getEntry(entry)).use { i -> out.outputStream().use { i.copyTo(it) } } }
            return out
        }
        if (names.any { it.startsWith("Payload/") && ".app/" in it }) {
            return File(into, BuildFiles.safeName(name.removeSuffix(".ipa")) + ".ipa").also { zip.copyTo(it, overwrite = true) }
        }
        extract(zip, into)
        appIn(into)?.let { return it }
        // The bundle's contents at the root: move them into <executable>.app.
        if (File(into, "Info.plist").isFile) {
            val exe = info(File(into, "Info.plist"))["CFBundleExecutable"] ?: "App"
            val app = File(into, "${BuildFiles.safeName(exe)}.app")
            val staging = File(into.parentFile, into.name + ".app-tmp").apply { deleteRecursively() }
            if (!into.renameTo(staging)) throw IllegalStateException("could not move ${into.path}")
            into.mkdirs()
            if (!staging.renameTo(app)) throw IllegalStateException("could not move the app into ${app.path}")
            return app
        }
        throw NotASimulatorBuild("${zip.name} has no .app bundle inside (no Info.plist)")
    }

    /** The `.ipa` file in [d], if any. */
    fun ipaIn(d: File): File? = d.listFiles { f -> f.isFile && f.name.endsWith(".ipa") }?.firstOrNull()

    private fun ipaEntry(ipa: File, file: String): ByteArray? = ZipFile(ipa).use { z ->
        val e = z.entries().asSequence().firstOrNull { Regex("Payload/[^/]+\\.app/" + Regex.escape(file)).matches(it.name) } ?: return null
        z.getInputStream(e).readBytes()
    }

    /** Bundle id and versions from the `.ipa`'s Info.plist. */
    fun ipaManifest(ipa: File): ManifestInfo {
        val bytes = ipaEntry(ipa, "Info.plist") ?: throw NotASimulatorBuild("${ipa.name} has no Payload/<name>.app/Info.plist (not an .ipa?)")
        val tmp = File.createTempFile("ipa-info", ".plist")
        try {
            tmp.writeBytes(bytes)
            return manifest(tmp.parentFile, tmp)
        } finally { tmp.delete() }
    }

    /**
     * When the `.ipa`'s signing profile expires (`embedded.mobileprovision`, a signed plist whose XML is
     * readable as text); null without one (a simulator build, an App Store build).
     */
    fun profileExpiry(ipa: File): java.time.Instant? {
        if (!ipa.isFile) return null
        val text = runCatching { ipaEntry(ipa, "embedded.mobileprovision") }.getOrNull()?.toString(Charsets.ISO_8859_1) ?: return null
        val m = Regex("<key>ExpirationDate</key>\\s*<date>([^<]+)</date>").find(text) ?: return null
        return runCatching { java.time.Instant.parse(m.groupValues[1].trim()) }.getOrNull()
    }

    /** The `.app` folder at the top of [d] (or one level down), if any. */
    fun appIn(d: File): File? = d.listFiles { f -> f.isDirectory && f.name.endsWith(".app") }?.firstOrNull()
        ?: d.listFiles { f -> f.isDirectory }?.firstNotNullOfOrNull { s -> s.listFiles { f -> f.isDirectory && f.name.endsWith(".app") }?.firstOrNull() }

    /**
     * `ditto` on a Mac, which keeps the executable bit and the frameworks' symlinks (an app whose
     * binary lost its x bit installs but never starts); plain unzip elsewhere (tests).
     */
    private fun extract(zip: File, into: File) {
        if (HostOs.current() == HostOs.MAC) {
            val p = ProcessBuilder("ditto", "-x", "-k", zip.path, into.path).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            if (p.waitFor() != 0) throw IllegalStateException("ditto could not unpack ${zip.name}: ${out.take(200)}")
            return
        }
        val root = into.canonicalFile
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IllegalStateException("unsafe path in ${zip.name}: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }

    /** Bundle id and versions from the app's Info.plist, as an APK's manifest gives them. */
    fun manifest(app: File, plist: File = File(app, "Info.plist")): ManifestInfo {
        val i = info(plist.takeIf { it.isFile } ?: throw NotASimulatorBuild("${app.name} has no Info.plist"))
        val id = i["CFBundleIdentifier"] ?: throw NotASimulatorBuild("${app.name}: Info.plist has no CFBundleIdentifier")
        return ManifestInfo(id, i["CFBundleVersion"]?.toLongOrNull(), i["CFBundleShortVersionString"] ?: i["CFBundleVersion"], minSdk = null, targetSdk = null)
    }

    /** The plist's top-level strings: `plutil` reads binary plists on a Mac; XML ones are read here. */
    fun info(plist: File): Map<String, String> {
        if (HostOs.current() == HostOs.MAC) {
            val p = ProcessBuilder("plutil", "-convert", "json", "-o", "-", plist.path).start()
            val out = p.inputStream.readBytes().decodeToString()
            if (p.waitFor() == 0) return (Json.parseToJsonElement(out) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }.toMap()
        }
        val bytes = plist.readBytes()
        if (bytes.size >= 6 && String(bytes, 0, 6) == "bplist") throw NotASimulatorBuild("${plist.path} is a binary plist: it can only be read on a Mac")
        val f = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val dict = f.newDocumentBuilder().parse(plist).getElementsByTagName("dict").item(0) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val kids = dict.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i) as? org.w3c.dom.Element ?: continue
            if (n.tagName == "key") { key = n.textContent; continue }
            if (key != null && n.tagName in setOf("string", "integer", "real")) out[key] = n.textContent
            key = null
        }
        return out
    }

    /** What InstalledBuild records for a bundle: the hash of its Info.plist and executable. */
    fun fingerprint(app: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val exe = runCatching { info(File(app, "Info.plist"))["CFBundleExecutable"] }.getOrNull()
        for (f in listOfNotNull(File(app, "Info.plist"), exe?.let { File(app, it) })) if (f.isFile) f.inputStream().use { s ->
            val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
