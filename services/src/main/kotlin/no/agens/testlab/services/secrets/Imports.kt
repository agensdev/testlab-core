package no.agens.testlab.services.secrets

import com.github.javakeyring.Keyring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Bring existing credentials in, only from sources the user picks (the app never goes looking
 * for secrets in the home folder by itself, as the saying goes).
 */
object Imports {
    data class Result(val imported: List<String>, val skipped: List<String> = emptyList()) {
        override fun toString() = "imported ${imported.size}: ${imported.joinToString()}" +
            if (skipped.isNotEmpty()) "; skipped ${skipped.joinToString()}" else ""
    }

    /** Variable names in `.env` files and where they go: the common names and our own. */
    private val ENV_MAP = linkedMapOf(
        "GITHUB_TOKEN" to SecretStore.GITHUB_TOKEN,
        "GH_TOKEN" to SecretStore.GITHUB_TOKEN,
        "JIRA_EMAIL" to SecretStore.JIRA_EMAIL,
        "ATLASSIAN_EMAIL" to SecretStore.JIRA_EMAIL,
        "JIRA_API_TOKEN" to SecretStore.JIRA_TOKEN,
        "ATLASSIAN_API_TOKEN" to SecretStore.JIRA_TOKEN,
        "TYPESAFE_API_KEY" to SecretStore.TYPESAFE_KEY,
        "OPENAI_API_KEY" to SecretStore.OPENAI_KEY,
    )

    /** A user-picked `.env` file: `KEY=value` lines, optional quotes and `export`. */
    fun dotEnv(file: File, into: SecretStore): Result {
        val found = LinkedHashMap<String, String>()
        for (raw in file.readLines()) {
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) continue
            val k = line.substringBefore('=').trim()
            val v = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            ENV_MAP[k]?.let { target -> if (v.isNotEmpty() && target !in found) found[target] = v }
        }
        found.forEach { (k, v) -> into.put(k, v) }
        return Result(found.keys.toList())
    }

    /** A lab-style `lab-credentials.json`: `device_pin` and `accounts.<name>.<field>`. Returns the account names. */
    fun labCredentials(file: File, into: SecretStore): Pair<Result, List<String>> {
        val root = Json.parseToJsonElement(file.readText()) as JsonObject
        val imported = mutableListOf<String>()
        (root["device_pin"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { into.put(SecretStore.DEVICE_PIN, it); imported += "device_pin" }
        val accounts = (root["accounts"] as? JsonObject).orEmpty()
        for ((name, fields) in accounts) {
            for ((field, v) in (fields as? JsonObject).orEmpty()) {
                val value = (v as? JsonPrimitive)?.content ?: continue
                into.put(SecretStore.account(name, field), value)
                imported += "$name.$field"
            }
        }
        return Result(imported) to accounts.keys.toList()
    }

    /**
     * Another app's Keychain items on macOS ([service], keychain account → vault key), as a
     * profile lists them under imports.keychain. macOS asks the user to allow the access, because
     * the items belong to the other app's signature.
     */
    fun keychain(service: String, items: Map<String, String>, into: SecretStore): Result {
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        Keyring.create().use { k ->
            for ((account, target) in items) {
                val v = runCatching { k.getPassword(service, account) }.getOrNull()
                if (v.isNullOrBlank()) skipped += account else { into.put(target, v); imported += account }
            }
        }
        return Result(imported, skipped)
    }
}
