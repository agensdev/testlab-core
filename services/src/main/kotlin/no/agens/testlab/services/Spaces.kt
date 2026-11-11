package no.agens.testlab.services

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import java.io.File

/**
 * Spaces: one per client, so one client's keys, settings, reports and phones never reach another's.
 * Each space has its own folders ([AppDirs.forHost]), vault and Keychain item, port, service and MCP
 * names. The space a process works in comes from `--space` ([override]), else `TESTLAB_SPACE`, else
 * the one `space use` chose last; `default` is the folders as they were before spaces.
 */
object Spaces {
    const val DEFAULT = "default"
    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,31}")
    /** Names a space can't have: the folders the default space keeps in its workspace. */
    private val RESERVED = setOf(DEFAULT, "spaces", "plans", "reports", "skills", "leases")

    /** Set from `--space` before anything opens the runtime. */
    @Volatile var override: String? = null

    fun valid(name: String): Boolean = NAME.matches(name) && name !in RESERVED
    fun check(name: String): String = name.also { require(it == DEFAULT || valid(it)) { "a space name is lower-case letters, digits and dashes (up to 32), not ${RESERVED.joinToString()}: '$it'" } }

    fun current(env: Map<String, String> = System.getenv(), root: AppDirs = AppDirs.root()): String {
        override?.let { return check(it) }
        env["TESTLAB_SPACE"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return check(it) }
        return runCatching { File(root.data, "space").readText().trim() }.getOrNull()?.takeIf { it == DEFAULT || valid(it) } ?: DEFAULT
    }

    /** The spaces on this computer: `default`, then the made ones. */
    fun list(root: AppDirs = AppDirs.root()): List<String> =
        listOf(DEFAULT) + File(root.data, "spaces").listFiles().orEmpty().filter { it.isDirectory && valid(it.name) }.map { it.name }.sorted()

    /** Makes [name] the space later commands use when neither `--space` nor TESTLAB_SPACE says. */
    fun use(name: String, root: AppDirs = AppDirs.root()) {
        check(name)
        root.data.mkdirs()
        File(root.data, "space").writeText(name + "\n")
    }

    /** "" for the default space, "-acme" for acme: what names get so two spaces' don't collide. */
    fun suffix(space: String = current()): String = if (space == DEFAULT) "" else "-$space"

    /** The MCP server's name in Claude Code, Claude Desktop and Codex: `testlab`, `testlab-acme`. */
    fun mcpName(space: String = current()): String = AppIdentity.SLUG + suffix(space)

    /** The arguments that start a launcher in [space]: none for the default one. */
    fun args(space: String = current()): List<String> = if (space == DEFAULT) emptyList() else listOf("--space", space)

    /** A space's own folders under the default ones. */
    internal fun dirs(root: AppDirs, space: String, os: HostOs, home: File): AppDirs = AppDirs(
        data = File(root.data, "spaces/$space"),
        cache = File(root.cache, "spaces/$space"),
        // Outside ~/Documents, which iCloud may sync: ~/Testlab/<space> (Linux ~/testlab/<space>).
        workspace = File(home, (if (os == HostOs.LINUX) AppIdentity.SLUG else AppIdentity.NAME) + "/" + space),
    )
}
