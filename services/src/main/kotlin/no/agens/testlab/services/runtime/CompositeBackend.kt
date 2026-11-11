package no.agens.testlab.services.runtime

import no.agens.testlab.core.Platform
import no.agens.testlab.device.adb.LabDevice
import no.agens.testlab.device.hs.HsEndpoint

/**
 * Android and iOS devices in one list: each backend lists its own, and a serial goes back to the
 * backend that listed it. A backend that fails to list (no adb, no Xcode) leaves the others' devices.
 */
class CompositeBackend(private val backends: Map<Platform, DeviceBackend>) : DeviceBackend {
    private val owner = java.util.concurrent.ConcurrentHashMap<String, Platform>()

    override fun devices(): List<LabDevice> {
        val errors = mutableListOf<Throwable>()
        val all = backends.flatMap { (p, b) ->
            runCatching { b.devices() }.onFailure { errors += it }.getOrDefault(emptyList()).onEach { owner[it.serial] = p }
        }
        // With one backend (Android only, the usual case off macOS) its error stays what it was: «adb was not found».
        if (all.isEmpty() && errors.size == backends.size && errors.isNotEmpty()) throw errors.first()
        return all
    }

    fun platformOf(serial: String): Platform = owner[serial] ?: run { devices(); owner[serial] } ?: Platform.ANDROID

    private fun backendFor(serial: String): DeviceBackend = backends[platformOf(serial)] ?: backends.values.first()

    override fun connect(serial: String, takeOver: Boolean): HsEndpoint = backendFor(serial).connect(serial, takeOver)
    override fun release(serial: String) = backendFor(serial).release(serial)
}
