package no.agens.testlab.services.update

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.Spaces
import no.agens.testlab.services.builds.BuildJob
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.service.DesktopNotice
import java.io.File
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * What the running server does about new releases, one [tick] at a time.
 *
 * With the `checkUpdates` setting it looks for a newer release (at most once a day, see
 * [UpdateCheck]) and says so once: in the log and as a desktop notification. With `autoUpdate` as
 * well, and under a service manager ([supervised]: launchd or systemd, which start the server again),
 * it installs the release itself while nothing is going on ([idle]) and asks for a restart.
 *
 * Several servers can share one install (a server per space): one of them installs, under a lock
 * beside the install, and the others see that the files under them were replaced and restart too,
 * as they also do after `upgrade` or `upgrade --rollback` from a terminal. A version that was
 * installed this way is never installed a second time, so a rollback stays rolled back.
 */
class AutoUpdate(
    private val rt: LabRuntime,
    private val log: PrintStream,
    private val restart: () -> Unit,
    private val supervised: Boolean = System.getenv("TESTLAB_SERVICE") == "1" && HostOs.current() != HostOs.WINDOWS,
    private val upgrader: Upgrader = Upgrader(rt),
    private val home: File? = upgrader.cliHome(),
    private val notice: (String, String) -> Unit = DesktopNotice::show,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val startedWith = installId()
    private var told: String? = null
    private var failedAt = 0L

    /** Names the files the server runs from: it changes when an upgrade or rollback swaps the install. */
    private fun installId(): Any? = home?.let { h ->
        runCatching { Files.readAttributes(File(h, "lib").toPath(), BasicFileAttributes::class.java).fileKey() }.getOrNull()
    }

    /** The version this way of updating last installed, kept beside the install: shared by every space's server. */
    private val installedMark: File? get() = home?.let { File(it.parentFile, ".${it.name}-auto-update") }
    private val lockFile: File? get() = home?.let { File(it.parentFile, ".${it.name}-upgrade.lock") }

    /** Nothing a restart would cut off: no tool call for [QUIET_MS], no build on its way to a phone, nobody asked for a yes. */
    fun idle(): Boolean {
        val last = rt.recentCalls().lastOrNull()?.first ?: 0L
        if (now() - last < QUIET_MS) return false
        if (rt.builds.jobs.value.any { it.phase == BuildJob.Phase.DOWNLOADING || it.phase == BuildJob.Phase.INSTALLING }) return false
        return rt.approvals.pending.value.isEmpty()
    }

    suspend fun tick() {
        val s = rt.settings.current
        if (supervised && startedWith != null && installId() != startedWith) {
            if (idle()) { log.println("the install was replaced (an upgrade or a rollback): restarting with it"); restart() }
            return
        }
        if (!s.checkUpdates) return
        val u = rt.updates.check() ?: return
        val auto = s.autoUpdate && supervised && home != null && startedWith != null
        if (!auto || installedMark?.takeIf { it.isFile }?.readText()?.trim() == u.version) {
            if (told != u.version) {
                told = u.version
                log.println("${AppIdentity.NAME} ${u.version} is available (this is ${AppIdentity.VERSION}): ${AppIdentity.SLUG} upgrade")
                notice("${AppIdentity.NAME} ${u.version} is available", "Install it with: ${AppIdentity.SLUG} upgrade")
            }
            return
        }
        if (now() - failedAt < RETRY_MS || !idle()) return
        if (install(u)) restart()
    }

    /** True when the new version is in place (by this server or by another one meanwhile). */
    private suspend fun install(u: UpdateInfo): Boolean {
        val h = home ?: return false
        return try {
            RandomAccessFile(lockFile!!, "rw").channel.use { ch ->
                val lock = ch.tryLock() ?: return false // another server is installing: the next tick sees its work
                try {
                    if (installId() != startedWith) return true
                    log.println("auto-update: ${AppIdentity.VERSION} → ${u.version}")
                    val build = upgrader.latestRelease() ?: return false
                    val line = upgrader.installCli(build.cli, h)
                    installedMark?.writeText(build.version ?: u.version)
                    val back = (listOf(AppIdentity.SLUG) + Spaces.args() + listOf("upgrade", "--rollback")).joinToString(" ")
                    log.println("auto-update: installed $line; the old version is kept ($back). Restarting.")
                    notice("${AppIdentity.NAME} updated to ${build.version ?: u.version}", "The old version is kept: $back")
                    true
                } finally {
                    runCatching { lock.release() }
                }
            }
        } catch (e: Exception) {
            failedAt = now()
            log.println("auto-update to ${u.version} did not work (${e.message ?: e.toString()}); trying again later. By hand: ${AppIdentity.SLUG} upgrade")
            false
        }
    }

    companion object {
        const val QUIET_MS = 10 * 60_000L
        const val RETRY_MS = 6 * 3_600_000L
    }
}
