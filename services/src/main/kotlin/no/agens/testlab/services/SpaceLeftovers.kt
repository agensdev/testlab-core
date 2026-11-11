package no.agens.testlab.services

import java.io.File

/**
 * What the default space still holds while another space is in use: after a `space migrate`, a window
 * or service still on the default space keeps writing there (reports, proxy routings, installed
 * builds), and a phone routed from it keeps its proxy. Empty in the default space itself.
 */
object SpaceLeftovers {
    fun find(current: AppDirs, root: AppDirs = AppDirs.forHost(space = Spaces.DEFAULT)): List<String> {
        if (current.space == Spaces.DEFAULT || root.data.canonicalPath == current.data.canonicalPath) return emptyList()
        return listOfNotNull(
            "a running server (control.json): a service or serve is on the default space".takeIf { File(root.data, "control.json").isFile },
            File(root.workspace, "reports").listFiles().orEmpty().count { it.isDirectory }.takeIf { it > 0 }?.let { "$it report(s) in ${File(root.workspace, "reports").path}" },
            "proxy routings (proxied.json)".takeIf { File(root.data, "proxied.json").isFile },
            "the installed-builds record".takeIf { File(root.data, "installed-builds.json").isFile },
            "a vault".takeIf { root.vault.isFile },
        )
    }
}
