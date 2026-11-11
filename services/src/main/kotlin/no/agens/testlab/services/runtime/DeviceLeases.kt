package no.agens.testlab.services.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.AppIdentity
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/** The phone is held by another client's space; it isn't named, only that it's taken. */
class DeviceInUse(val serial: String) : RuntimeException(
    "$serial is in use by another client (another ${AppIdentity.NAME} space). Ask them to free it (${AppIdentity.SLUG} devices release $serial in their space), or use another phone.",
)

/**
 * Which space holds each shared phone: one lock file per phone in the folder all spaces share
 * (`<data>/leases/<serial>.json`, with the space and the process). A space takes a free phone the
 * first time it drives it; another space can't, until it is released. A lease whose process has
 * ended (and whose space runs no server) is stale and free to take, except a sticky one
 * (`devices lease`), which waits for `release`.
 *
 * Taking a phone from another space, or releasing one, runs [handover] for the space that had it
 * (its apps cleared, proxy off, TalkBack and font size back), so the next client starts clean.
 */
class DeviceLeases(
    private val dir: File,
    val space: String,
    private val handover: (serial: String, fromSpace: String) -> List<String>,
    private val pid: Long = ProcessHandle.current().pid(),
    private val alive: (Long) -> Boolean = { p -> ProcessHandle.of(p).map { it.isAlive }.orElse(false) },
    private val now: () -> Long = System::currentTimeMillis,
    /** The pid of a space's running server (its control.json), which keeps that space's leases live. */
    private val serverPid: (String) -> Long? = { null },
) {
    @Serializable
    data class Lease(val serial: String, val space: String, val pid: Long, val sticky: Boolean = false, val since: String = "")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    /** Phones this process checked it holds, and when: the file is read again after [RECHECK_MS]. */
    private val held = HashMap<String, Long>()
    @Volatile private var inHandover: String? = null

    private fun file(serial: String) = File(dir, serial.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun get(serial: String): Lease? = runCatching { json.decodeFromString(Lease.serializer(), file(serial).readText()) }.getOrNull()

    /** Sticky, or its process runs, or the space's server does (a short CLI command took it for a running window). */
    fun live(l: Lease): Boolean = l.sticky || alive(l.pid) || serverPid(l.space)?.let(alive) == true

    /** Every lease, live or stale. */
    fun all(): List<Lease> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching { json.decodeFromString(Lease.serializer(), f.readText()) }.getOrNull()
    }

    /** "free", "yours" or "other" for showing a phone in a list. */
    fun state(serial: String): String = when {
        get(serial)?.let { live(it) } != true -> "free"
        get(serial)?.space == space -> "yours"
        else -> "other"
    }

    /** Held by another space right now. */
    fun takenByOther(serial: String): Boolean = get(serial)?.let { it.space != space && live(it) } == true

    /**
     * Holds [serial] for this space before anything drives it: free or stale → ours (a stale lease of
     * another space's gets its hand-over first); live and another space's → [DeviceInUse].
     * Returns the hand-over steps that ran, if any.
     */
    fun ensure(serial: String, sticky: Boolean = false): List<String> {
        require(serial.isNotBlank()) { "no device serial" }
        if (inHandover == serial) return emptyList()
        if (peeking.get() == true && !sticky) { if (takenByOther(serial)) throw DeviceInUse(serial); return emptyList() }
        synchronized(held) { held[serial]?.takeIf { !sticky && now() - it < RECHECK_MS }?.let { return emptyList() } }
        val (previous, mine) = locked {
            val cur = get(serial)
            when {
                cur != null && cur.space == space -> {
                    if (cur.pid != pid && !cur.sticky || sticky && !cur.sticky) write(cur.copy(pid = pid, sticky = cur.sticky || sticky))
                    null to true
                }
                cur != null && live(cur) -> null to false
                else -> {
                    write(Lease(serial, space, pid, sticky, Instant.ofEpochMilli(now()).toString()))
                    cur?.space to true
                }
            }
        }
        if (!mine) { synchronized(held) { held.remove(serial) }; throw DeviceInUse(serial) }
        synchronized(held) { held[serial] = now() }
        return previous?.let { runHandover(serial, it) }.orEmpty()
    }

    private val peeking = ThreadLocal<Boolean>()

    /** Runs [block] (status reads for a list) without taking free phones; another space's still refuse. */
    fun <T> peek(block: () -> T): T {
        val was = peeking.get()
        peeking.set(true)
        try { return block() } finally { peeking.set(was) }
    }

    /** Frees [serial] after this space's hand-over; another space's live lease is not ours to free. */
    fun release(serial: String, handover: Boolean = true): List<String> {
        val cur = get(serial) ?: return emptyList()
        if (cur.space != space && live(cur)) throw DeviceInUse(serial)
        val steps = if (handover) runHandover(serial, cur.space) else emptyList()
        locked { if (get(serial) == cur || get(serial)?.space == cur.space) file(serial).delete() }
        synchronized(held) { held.remove(serial) }
        return steps
    }

    private fun runHandover(serial: String, from: String): List<String> {
        inHandover = serial
        try { return handover(serial, from) } finally { inHandover = null }
    }

    private fun write(l: Lease) {
        dir.mkdirs()
        val f = file(l.serial)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Lease.serializer(), l))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    /** One space at a time decides (the lock is the OS's, across processes). */
    private fun <T> locked(block: () -> T): T {
        dir.mkdirs()
        // The OS lock is per process: threads of this one queue on the monitor first.
        synchronized(JVM_LOCK) { RandomAccessFile(File(dir, ".lock"), "rw").use { raf -> raf.channel.lock().use { return block() } } }
    }

    companion object {
        const val RECHECK_MS = 5_000L
        private val JVM_LOCK = Any()
    }
}
