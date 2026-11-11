package no.agens.testlab.cli

import no.agens.testlab.AppIdentity

/** `testlab simulator [list|models|create [MODEL]|start NAME|stop NAME] [--no-start]`: iPhone simulators. */
internal fun simulator(opts: Opts): Int {
    val sims = runtime.simulators
    val args = opts.positional()
    when (args.firstOrNull() ?: "list") {
        "list" -> {
            val list = sims.list()
            if (list.isEmpty()) println("No iPhone simulators. Make one: ${AppIdentity.SLUG} simulator create")
            list.forEach { s -> println("${s.name.padEnd(28)} ${s.runtime.padEnd(10)} ${if (s.booted) "booted " else "stopped"}  ${s.udid}") }
        }
        "models" -> sims.models().forEach(::println)
        "create" -> {
            val model = args.drop(1).joinToString(" ").ifBlank { null }
            println("Making ${model ?: "an iPhone"} simulator …")
            val s = sims.create(model) { println("  $it") }
            println("  made ${s.name} (${s.runtime}): ${s.udid}")
            if (!opts.flag("no-start")) println("  booted: ${sims.start(s.udid).udid}")
        }
        "start" -> {
            val name = args.drop(1).joinToString(" ").ifBlank { null } ?: sims.list().singleOrNull()?.udid ?: throw CliError("which one? ${AppIdentity.SLUG} simulator list")
            val s = sims.start(name); println("booted ${s.name} (${s.runtime}): ${s.udid}")
        }
        "stop" -> {
            val name = args.drop(1).joinToString(" ").ifBlank { null } ?: sims.list().singleOrNull { it.booted }?.udid ?: throw CliError("which one? ${AppIdentity.SLUG} simulator list")
            val s = sims.list().firstOrNull { it.udid == name || it.name.equals(name, ignoreCase = true) } ?: throw CliError("no simulator '$name'")
            runtime.drop(s.udid); runtime.xcrun?.shutdown(s.udid); println("stopped ${s.name}")
        }
        else -> throw CliError("usage: ${AppIdentity.SLUG} simulator [list|models|create [MODEL]|start NAME|stop NAME] [--no-start]")
    }
    return 0
}

/**
 * `testlab xcode [status|enable|approve|open PATH]`: Xcode 27's MCP server, which the iOS crash and
 * performance tools read through. Headless mode and approvals need sudo: those commands run in this
 * terminal so macOS can ask for the password.
 */
internal fun xcode(opts: Opts): Int {
    val x = runtime.xcode
    fun sudo(vararg args: String): Int = ProcessBuilder(listOf("sudo", "/usr/bin/xcrun", "mcp-server") + args).inheritIO().start().waitFor()
    when (val sub = opts.positional().firstOrNull() ?: "status") {
        "status" -> {
            val s = x.status()
            println("Xcode      ${s.xcode ?: "not found (install Xcode 27 from the App Store, open it once)"}")
            println("Headless   ${if (s.headless == null) "off or not available (`${AppIdentity.SLUG} xcode enable`)" else "on"}")
            if (s.pending.isNotEmpty()) println("Waiting    ${s.pending.size} request(s) for approval: ${AppIdentity.SLUG} xcode approve")
            s.headless?.let { println("\n$it") }
        }
        "enable" -> {
            println("Turning on Xcode's headless MCP server (macOS asks for your password) …")
            val rc = sudo("enable")
            if (rc == 0) println("On. The first time ${AppIdentity.NAME} uses it, Xcode asks for an approval: ${AppIdentity.SLUG} xcode approve")
            return rc
        }
        "approve" -> {
            val pending = x.status().pending
            if (pending.isEmpty()) { println("Nothing is waiting for an approval. (Ask Claude for the iOS crashes first: that makes the request.)"); return 0 }
            // Testlab's Java isn't signed with a developer team, so Xcode only grants it for a day at a time.
            var rc = 0
            for (id in pending) { println("Approving $id for 24 hours …"); rc = maxOf(rc, sudo("approve", id, "--for-24-hours")) }
            return rc
        }
        "open" -> {
            val path = opts.positional().getOrNull(1) ?: throw CliError("usage: ${AppIdentity.SLUG} xcode open PATH (the app's .xcodeproj or .xcworkspace)")
            return ProcessBuilder("/usr/bin/xcrun", "mcp-server", "open", java.io.File(path).absolutePath).inheritIO().start().waitFor()
        }
        else -> throw CliError("usage: ${AppIdentity.SLUG} xcode [status|enable|approve|open PATH] (not '$sub')")
    }
    return 0
}
