package no.agens.testlab.services.runtime

import no.agens.testlab.device.adb.AdbCli
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.hs.BundledJar
import no.agens.testlab.device.hs.HsDaemon
import no.agens.testlab.device.hs.HsEndpoint
import java.io.File

/** How the runtime reaches phones: adb and the bundled daemon; tests plug in fakes. */
interface DeviceBackend {
    fun devices(): List<AdbDevice>
    /** Starts (or re-uses) the daemon on [serial] and returns where it listens on this computer. */
    fun connect(serial: String, takeOver: Boolean): HsEndpoint
    fun release(serial: String)
}

internal class AdbDeviceBackend(private val adb: () -> AdbCli, private val cacheDir: File) : DeviceBackend {
    private val daemon by lazy { HsDaemon(adb(), BundledJar.load(), cacheDir) }
    override fun devices(): List<AdbDevice> = adb().devices()
    override fun connect(serial: String, takeOver: Boolean): HsEndpoint = daemon.connect(serial, takeOver)
    override fun release(serial: String) = daemon.release(serial)
}
