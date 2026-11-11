package no.agens.testlab.services

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import java.io.File

/**
 * Where ${AppIdentity.NAME} keeps its files on each OS.
 *
 * - [data]: settings, the secret vault, decision traces (private to the user).
 * - [cache]: downloaded builds and the bundled daemon jar; safe to delete.
 * - [workspace]: plans and reports, which users open, share and attach to Jira.
 */
data class AppDirs(val data: File, val cache: File, val workspace: File) {
    val traces: File get() = File(data, "traces")
    val vault: File get() = File(data, "secrets.vault")
    val settings: File get() = File(data, "settings.json")
    val builds: File get() = File(cache, "builds")
    val plans: File get() = File(workspace, "plans")
    val reports: File get() = File(workspace, "reports")
    /** The space's rules (allowed GitHub owners, Jira sites, apps, code roots): [SpaceRules]. */
    val rules: File get() = File(data, "space.json")

    /** Which space these are: `<root>/spaces/<name>`, else the default one. */
    val space: String get() = if (data.parentFile?.name == "spaces") data.name else Spaces.DEFAULT

    /** The default space's data folder, under which every space lives. */
    val rootData: File get() = if (space == Spaces.DEFAULT) data else data.parentFile.parentFile

    /** The data folders of the other spaces on this computer (for their published secret hashes). */
    fun otherSpacesData(): List<File> {
        val all = listOf(rootData) + File(rootData, "spaces").listFiles().orEmpty().filter { it.isDirectory && Spaces.valid(it.name) }
        return all.filter { it.canonicalPath != data.canonicalPath }
    }

    companion object {
        /** The folders of [space] ([Spaces.current] by default): the per-OS ones for `default`, else its own under them. */
        fun forHost(
            os: HostOs = HostOs.current(),
            env: Map<String, String> = System.getenv(),
            home: File = File(System.getProperty("user.home")),
            space: String = Spaces.current(env, root(os, env, home)),
        ): AppDirs {
            val root = root(os, env, home)
            return if (space == Spaces.DEFAULT) root else Spaces.dirs(root, Spaces.check(space), os, home)
        }

        /** The per-OS folders: the default space's, and where the other spaces live. */
        fun root(
            os: HostOs = HostOs.current(),
            env: Map<String, String> = System.getenv(),
            home: File = File(System.getProperty("user.home")),
        ): AppDirs = when (os) {
            HostOs.MAC -> AppDirs(
                data = File(home, "Library/Application Support/${AppIdentity.NAME}"),
                cache = File(home, "Library/Caches/${AppIdentity.NAME}"),
                workspace = File(home, "Documents/${AppIdentity.NAME}"),
            )
            HostOs.WINDOWS -> {
                val roaming = env["APPDATA"]?.let(::File) ?: File(home, "AppData/Roaming")
                val local = env["LOCALAPPDATA"]?.let(::File) ?: File(home, "AppData/Local")
                AppDirs(
                    data = File(roaming, AppIdentity.NAME),
                    cache = File(local, "${AppIdentity.NAME}/Cache"),
                    workspace = File(home, "Documents/${AppIdentity.NAME}"),
                )
            }
            HostOs.LINUX -> {
                fun xdg(name: String, fallback: String) =
                    env[name]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, fallback)
                AppDirs(
                    data = File(xdg("XDG_DATA_HOME", ".local/share"), AppIdentity.SLUG),
                    cache = File(xdg("XDG_CACHE_HOME", ".cache"), AppIdentity.SLUG),
                    workspace = File(home, AppIdentity.SLUG),
                )
            }
        }
    }
}

/** `TESTLAB_<name>` from the environment, falling back to the older `NTL_<name>`. */
fun env(name: String, environment: Map<String, String> = System.getenv()): String? =
    environment["TESTLAB_$name"]?.takeIf { it.isNotEmpty() } ?: environment["NTL_$name"]?.takeIf { it.isNotEmpty() }

/**
 * The vault passphrase for headless use: `TESTLAB_VAULT_PASSPHRASE`, else the first line of the file
 * `TESTLAB_VAULT_PASSPHRASE_FILE` names, else systemd's credential `vault-passphrase`
 * (`LoadCredential=` in a service unit), so the passphrase need not sit in the unit or the environment.
 */
fun vaultPassphrase(environment: Map<String, String> = System.getenv()): CharArray? {
    env("VAULT_PASSPHRASE", environment)?.let { return it.toCharArray() }
    val file = env("VAULT_PASSPHRASE_FILE", environment)?.let(::File)
        ?: environment["CREDENTIALS_DIRECTORY"]?.let { File(it, "vault-passphrase") }?.takeIf { it.isFile }
        ?: return null
    return runCatching { file.readLines().firstOrNull()?.trimEnd('\r')?.takeIf { it.isNotEmpty() }?.toCharArray() }.getOrNull()
        ?: throw IllegalStateException("the vault passphrase file ${file.path} is missing or empty")
}
