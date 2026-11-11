package no.agens.testlab.services.runtime

import no.agens.testlab.device.adb.AdbDevice

/** Which device work goes to when nobody named one: a real phone before an emulator. */
object DevicePick {
    /** For calls without a serial: the only ready device, else the only ready phone, else the caller must say which. */
    fun default(devices: List<AdbDevice>): String {
        val ready = devices.filter { it.ready }
        val phones = ready.filterNot { it.isEmulator }
        return when {
            ready.size == 1 -> ready.single().serial
            phones.size == 1 -> phones.single().serial
            ready.isEmpty() -> {
                val waiting = devices.firstOrNull()
                throw NoDevice(waiting?.let { "${it.serial} is ${it.state}: ${it.hint}" } ?: "No device: plug in a phone with USB debugging on, or start an emulator.")
            }
            else -> throw NoDevice("Several ${if (phones.size > 1) "phones" else "devices"} are connected (${(phones.ifEmpty { ready }).joinToString { it.serial }}): pass a serial.")
        }
    }

    /**
     * The device an action without a serial takes: what the person chose, else the connected device when
     * [preferActive], else a phone, else the connected device, else any ready one. Null: nothing ready.
     */
    fun target(ready: List<AdbDevice>, active: String?, chosen: String? = null, preferActive: Boolean = false): String? {
        fun ok(s: String?) = s?.takeIf { c -> ready.any { it.serial == c } }
        return ok(chosen)
            ?: (if (preferActive) ok(active) else null)
            ?: ready.firstOrNull { !it.isEmulator }?.serial
            ?: ok(active)
            ?: ready.firstOrNull()?.serial
    }
}
