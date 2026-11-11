package no.agens.testlab.services.runtime

import no.agens.testlab.device.ios.Simulator
import no.agens.testlab.device.ios.XcrunCli

/**
 * iOS Simulators that set themselves up: a Mac with Xcode but no simulator (or no iOS runtime)
 * gets one in a step, the counterpart of [Emulators] for Android. [xcrun] is null off macOS.
 */
class Simulators(private val xcrun: () -> XcrunCli?) {
    private fun x(): XcrunCli = xcrun() ?: throw NotOnPlatform("iOS Simulators need macOS with Xcode")

    /** The iPhone simulators, booted ones first. */
    fun list(): List<Simulator> = x().simulators().filter { it.isPhone }.sortedWith(compareByDescending<Simulator> { it.booted }.thenByDescending { it.runtime })

    /** The iPhone models this Xcode can simulate, newest first. */
    fun models(): List<String> = XcrunCli.phoneCandidates(x().deviceTypes()).map { it.name }

    /**
     * Makes an iPhone simulator ([model] or the newest plain iPhone) on the newest iOS runtime,
     * downloading the runtime first when there is none ([progress] says so: several GB). Returns it.
     */
    fun create(model: String? = null, progress: (String) -> Unit = {}): Simulator {
        val x = x()
        var runtimes = x.runtimes()
        if (runtimes.isEmpty()) {
            progress("downloading the iOS simulator runtime (several GB, this takes a while)")
            x.downloadIosRuntime()
            runtimes = x.runtimes()
            if (runtimes.isEmpty()) throw IllegalStateException("no iOS runtime after the download: install one in Xcode → Settings → Components")
        }
        val runtime = runtimes.first()
        val types = XcrunCli.phoneCandidates(x.deviceTypes())
        val tries = if (model.isNullOrBlank()) types.take(4) else types.filter { it.name.equals(model.trim(), ignoreCase = true) }
            .ifEmpty { throw IllegalArgumentException("no iPhone model '$model': ${types.take(8).joinToString { it.name }}") }
        var last: Exception? = null
        for (t in tries) {
            // A runtime supports only some models: the newest phone may be too new or too old for it.
            val udid = try { x.create("${t.name} (Testlab)", t.identifier, runtime.identifier) } catch (e: Exception) { last = e; continue }
            return x.simulators().firstOrNull { it.udid == udid } ?: Simulator(udid, "${t.name} (Testlab)", "Shutdown", runtime.name, true)
        }
        throw IllegalStateException("couldn't make an iPhone simulator on ${runtime.name}: ${last?.message}")
    }

    /** Boots [nameOrUdid] (the newest runtime when several share the name) and shows it in the Simulator app. */
    fun start(nameOrUdid: String): Simulator {
        val x = x()
        val sims = x.simulators()
        val sim = sims.firstOrNull { it.udid == nameOrUdid } ?: sims.filter { it.name.equals(nameOrUdid, ignoreCase = true) }.maxByOrNull { it.runtime }
            ?: throw IllegalArgumentException("no simulator '$nameOrUdid'" + list().joinToString(prefix = ": there are ", transform = { it.name }).takeIf { list().isNotEmpty() }.orEmpty())
        x.boot(sim.udid)
        runCatching { ProcessBuilder("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", sim.udid).start() }
        return sim
    }
}
