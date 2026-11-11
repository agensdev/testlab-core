package no.agens.testlab.cli

import kotlinx.coroutines.runBlocking
import no.agens.testlab.services.runtime.Browser
import no.agens.testlab.services.tools.AndroidCli

/** `testlab android-cli [status|install|login|logout]`: Google's Android CLI, which emulators and Crashlytics use. */
internal fun androidCli(opts: Opts): Int {
    val cli = runtime.androidCli
    when (val sub = opts.positional().firstOrNull() ?: "status") {
        "install" -> {
            val url = cli.downloadUrl ?: run { println("On Windows: winget install -e --id Google.AndroidCLI"); return 1 }
            println("Downloading Google's Android CLI from $url into ~/.local/bin …")
            println("  " + runBlocking { cli.install() })
            println("Sign in for Crashlytics with: ${no.agens.testlab.AppIdentity.SLUG} android-cli login")
            return 0
        }
        "login" -> return cli.interactive(listOf("auth", "login"))
        "logout" -> return cli.interactive(listOf("auth", "logout"))
        "status" -> {
            val bin = cli.binary() ?: run { println(AndroidCli.MISSING); return 1 }
            println("Android CLI  ${cli.version() ?: "?"}  ($bin)")
            println("Google       ${cli.account() ?: "not signed in (${no.agens.testlab.AppIdentity.SLUG} android-cli login)"}")
            val apps = runtime.profile.crashlytics.apps
            println("Crashlytics  " + if (apps.isEmpty()) "the profile «${runtime.profile.name}» names no Firebase apps (crashlytics.apps)" else apps.keys.joinToString())
            return 0
        }
        else -> throw CliError("usage: ${no.agens.testlab.AppIdentity.SLUG} android-cli [status|install|login|logout] (not '$sub')")
    }
}

/** What went wrong as one line, not a stack trace: the emulator and the CLI fail in ordinary ways (no system image, not running). */
internal fun plainErrors(block: () -> Int): Int = try {
    block()
} catch (e: IllegalStateException) {
    throw CliError(e.message ?: "failed")
} catch (e: IllegalArgumentException) {
    throw CliError(e.message ?: "failed")
}

/** `testlab emulator [list|profiles|create PROFILE|start NAME|stop NAME] [--headless] [--cold]`. */
internal fun emulator(opts: Opts): Int {
    val emus = runtime.emulators
    val args = opts.positional()
    val headless = opts.flag("headless") || !Browser.available()
    when (args.firstOrNull() ?: "list") {
        "list" -> {
            val list = emus.avds()
            if (list.isEmpty()) println("No emulators. " + if (emus.canCreate) "Make one: ${no.agens.testlab.AppIdentity.SLUG} emulator create medium_phone" else AndroidCli.MISSING)
            list.forEach { e -> println(listOfNotNull(e.name.padEnd(24), (e.api ?: "").padEnd(12), if (e.running) "running ${e.serial}" else "stopped").joinToString(" ")) }
        }
        "profiles" -> emus.profiles().forEach(::println)
        "create" -> {
            if (!emus.canCreate) { println(AndroidCli.MISSING); return 1 }
            val profile = args.getOrNull(1) ?: "medium_phone"
            println("Making a $profile emulator (the first one downloads the emulator and a system image: a few GB) …")
            val name = emus.create(profile)
            println("  made $name")
            if (!opts.flag("no-start")) println("  running: serial ${emus.start(name, headless = headless)}")
        }
        "start" -> {
            val name = args.getOrNull(1) ?: emus.avds().singleOrNull()?.name ?: throw CliError("which one? ${no.agens.testlab.AppIdentity.SLUG} emulator list")
            println("Starting $name …")
            println("  running: serial ${emus.start(name, headless = headless, cold = opts.flag("cold"))}")
        }
        "stop" -> {
            val name = args.getOrNull(1) ?: emus.avds().singleOrNull { it.running }?.name ?: throw CliError("which one? ${no.agens.testlab.AppIdentity.SLUG} emulator list")
            emus.stop(name); println("stopped $name")
        }
        else -> throw CliError("usage: ${no.agens.testlab.AppIdentity.SLUG} emulator [list|profiles|create PROFILE|start NAME|stop NAME] [--headless] [--cold] [--no-start]")
    }
    return 0
}
