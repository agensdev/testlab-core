package no.agens.testlab.core

import no.agens.testlab.AppIdentity

/** Human-readable build line, shown in About, the CLI and the MCP server info. */
fun versionLine(runtime: String): String = "${AppIdentity.NAME} ${AppIdentity.VERSION} (${AppIdentity.BUILD}, $runtime)"

/**
 * jpackage on macOS refuses a version whose first number is 0, so 0.x builds are
 * packaged as 1.x on macOS only. The app always reports its real version.
 */
fun macPackageVersion(version: String): String {
    val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
    val (major, minor, patch) = (parts + listOf(0, 0, 0)).take(3)
    return if (major == 0) "1.$minor.$patch" else "$major.$minor.$patch"
}
