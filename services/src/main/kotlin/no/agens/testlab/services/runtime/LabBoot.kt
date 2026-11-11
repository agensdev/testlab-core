package no.agens.testlab.services.runtime

import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.secrets.VaultKeys
import no.agens.testlab.services.secrets.VaultSecretStore
import no.agens.testlab.services.settings.SettingsStore

/** Opens the runtime on this computer's folders: settings.json and the vault (OS keyring, DPAPI or passphrase). */
object LabBoot {
    fun open(
        dirs: AppDirs = AppDirs.forHost(),
        passphrase: () -> CharArray? = { no.agens.testlab.services.vaultPassphrase() },
    ): LabRuntime {
        val settings = SettingsStore(dirs.settings)
        val vault = VaultSecretStore(dirs.vault, VaultKeys.choose(dirs.data, passphrase = passphrase))
        return LabRuntime(dirs, settings, vault)
    }
}
