package no.agens.testlab.services.report

import no.agens.testlab.services.files.readTextCloud
import no.agens.testlab.services.files.readBytesCloud
import no.agens.testlab.services.files.readLinesCloud
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import no.agens.testlab.core.jev.ShotSlot
import no.agens.testlab.core.jev.ShotStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Plan screenshots into [dir], with the lab's semantics: when the folder is a report (it has a
 * manifest.json), shots are numbered `NN-<slug>.jpg` and registered in the manifest so the
 * report shows them with their captions; otherwise they get a timestamped name. [serial], the
 * device shown, is kept with each shot: a report takes the recordings of the devices it shows.
 */
class DirShotStore(
    private val dir: File,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val serial: String? = null,
) : ShotStore {
    private val manifest = File(dir, "manifest.json")
    private val json = Json { prettyPrint = false }

    @Synchronized
    override fun allocate(caption: String, slug: String): ShotSlot {
        dir.mkdirs()
        if (manifest.isFile) {
            val n = shots().size + 1
            val name = n.toString().padStart(2, '0') + "-$slug.jpg"
            return ShotSlot(File(dir, name).path, name, registered = true)
        }
        val name = LocalDateTime.now().format(STAMP) + "-$slug.jpg"
        return ShotSlot(File(dir, name).path, name, registered = false)
    }

    @Synchronized
    override fun commit(slot: ShotSlot, caption: String) {
        if (!slot.registered) return
        val man = Json.parseToJsonElement(manifest.readTextCloud()).jsonObject
        val shot = buildJsonObject { put("file", slot.fileName); put("caption", caption); put("t", nowSeconds()); serial?.let { put("serial", it) } }
        val updated = JsonObject(man + ("shots" to JsonArray(shots(man) + shot)))
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), updated))
        if (!tmp.renameTo(manifest)) { manifest.writeText(tmp.readText()); tmp.delete() }
    }

    private fun shots(man: JsonObject = Json.parseToJsonElement(manifest.readTextCloud()).jsonObject) =
        (man["shots"] as? JsonArray)?.toList() ?: emptyList()

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
