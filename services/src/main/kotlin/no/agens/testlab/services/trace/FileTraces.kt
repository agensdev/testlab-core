package no.agens.testlab.services.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.core.autopilot.TraceFactory
import no.agens.testlab.core.autopilot.TraceSink
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JSONL decision traces in the app's private data folder. Traces hold screen text (names,
 * balances), so the folder is created user-only where the OS supports POSIX permissions.
 */
class FileTraces(private val dir: File, private val redact: (String) -> String = { it }) : TraceFactory {
    override fun open(serial: String, tool: String): TraceSink? {
        if (!ensureDir()) return null
        val ts = LocalDateTime.now().format(STAMP)
        return FileTrace(File(dir, "$ts-${serial.ifEmpty { "device" }}-$tool.jsonl"), redact)
    }

    private fun ensureDir(): Boolean = try {
        if (!dir.isDirectory) {
            dir.mkdirs()
            runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
        dir.isDirectory
    } catch (e: Exception) {
        false
    }

    private class FileTrace(private val file: File, private val redact: (String) -> String) : TraceSink {
        override val path: String = file.path

        override fun write(rec: JsonObject) {
            val withTs = JsonObject(mapOf("ts" to JsonPrimitive(LocalDateTime.now().format(ISO_SECONDS))) + rec)
            val line = redact(Json.encodeToString(JsonObject.serializer(), withTs))
            try {
                file.appendText(line + "\n")
            } catch (e: Exception) {
                System.err.println("[traces] write failed (${e.message}); ${line.take(400)}")
            }
        }
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
