package no.agens.testlab.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a device runs, and so which profile, build kind and commands apply to it. */
@Serializable
enum class Platform(val label: String) {
    @SerialName("android") ANDROID("Android"),
    @SerialName("ios") IOS("iOS");

    val id: String get() = name.lowercase()

    companion object {
        fun of(id: String?): Platform? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}
