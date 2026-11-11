package no.agens.testlab.core.profile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/profile.schema.json is generated from the Profile classes, so it can't drift: this test
 * fails when they differ. `UPDATE_SCHEMA=1 ./gradlew :core:jvmTest` rewrites it.
 */
@OptIn(ExperimentalSerializationApi::class)
class ProfileSchemaTest {
    private fun schema(d: SerialDescriptor): JsonObject = if (d.serialName.startsWith("kotlinx.serialization.json.JsonObject")) {
        // `platforms` values: the profile's own fields, as the platform overrides them (JsonElement is recursive).
        buildJsonObject { put("type", "object"); put("description", "fields of this profile that differ on this platform, as at the top level") }
    } else when (d.kind) {
        PrimitiveKind.STRING -> buildJsonObject { put("type", "string") }
        PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
        PrimitiveKind.INT, PrimitiveKind.LONG -> buildJsonObject { put("type", "integer") }
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> buildJsonObject { put("type", "number") }
        kotlinx.serialization.descriptors.SerialKind.ENUM -> buildJsonObject { put("type", "string"); put("enum", buildJsonArray { for (i in 0 until d.elementsCount) add(JsonPrimitive(d.getElementName(i))) }) }
        StructureKind.LIST -> buildJsonObject { put("type", "array"); put("items", schema(d.getElementDescriptor(0))) }
        StructureKind.MAP -> buildJsonObject { put("type", "object"); put("additionalProperties", schema(d.getElementDescriptor(1))) }
        else -> buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { for (i in 0 until d.elementsCount) put(d.getElementName(i), schema(d.getElementDescriptor(i))) }
            val req = (0 until d.elementsCount).filter { !d.isElementOptional(it) }.map { d.getElementName(it) }
            if (req.isNotEmpty()) put("required", buildJsonArray { req.forEach { add(JsonPrimitive(it)) } })
        }
    }.let { o -> if (d.isNullable) JsonObject(o + ("type" to buildJsonArray { add(o.getValue("type")); add(JsonPrimitive("null")) })) else o }

    @Test
    fun theSchemaFileMatchesTheModel() {
        val s = JsonObject(
            mapOf(
                "\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema"),
                "title" to JsonPrimitive("Testlab project profile"),
                "description" to JsonPrimitive("profile.json of a Testlab profile; see docs/PROFILES.md. Unknown fields are ignored."),
            ) + schema(Profile.serializer().descriptor),
        )
        val text = Profile.json.encodeToString(JsonObject.serializer(), s) + "\n"
        val f = File("../docs/profile.schema.json")
        if (System.getenv("UPDATE_SCHEMA") == "1" || !f.exists()) f.writeText(text)
        assertEquals(text, f.readText(), "docs/profile.schema.json is out of date: run UPDATE_SCHEMA=1 ./gradlew :core:jvmTest")
    }
}
