package no.agens.testlab.cli

import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.versionLine
import no.agens.testlab.services.diag.SetupStatus

/**
 * `status`: the setup checklist (SetupStatus, the same one the settings page shows) with what to run for each gap.
 * Read-only. `--offline` skips the GitHub and Jira calls. Exit 1 when something essential is missing.
 */
internal fun status(opts: Opts): Int = runBlocking {
    val rt = runtime
    println(versionLine("Java ${System.getProperty("java.version")}"))
    println("  · space         ${no.agens.testlab.services.Spaces.current()}")
    println("  · data          ${rt.dirs.data.path}")
    rt.rules.current.takeIf { rt.rules.enforced }?.let { r ->
        println("  · rules         " + if (r.open) "none pinned yet (the first profile decides)" else listOfNotNull(
            r.githubOwners.takeIf { it.isNotEmpty() }?.let { "GitHub ${it.joinToString()}" },
            r.jiraSites.takeIf { it.isNotEmpty() }?.let { "Jira ${it.joinToString()}" },
            r.appFamilies.takeIf { it.isNotEmpty() }?.let { "apps ${it.joinToString()}" },
        ).joinToString(" · "))
    }
    // Shared phones: which this space holds, and how many another client's space does.
    val leases = rt.leases.all().filter { rt.leases.live(it) }
    if (leases.isNotEmpty()) {
        val mine = leases.filter { it.space == rt.leases.space }.map { it.serial + if (it.sticky) " (until released)" else "" }
        val others = leases.count { it.space != rt.leases.space }
        println("  · phones        " + listOfNotNull(mine.takeIf { it.isNotEmpty() }?.let { "held here: ${it.joinToString()}" }, others.takeIf { it > 0 }?.let { "$it in use by another client" }).joinToString(" · "))
    }
    no.agens.testlab.services.SpaceLeftovers.find(rt.dirs).takeIf { it.isNotEmpty() }?.let { left ->
        println("  ! default space still has ${left.joinToString("; ")}")
        println("                  move what is left: ${no.agens.testlab.AppIdentity.SLUG} space migrate ${rt.dirs.space} --rest (stop the service first)")
    }
    val items = SetupStatus(rt).check(online = !opts.flag("offline"))
    for (g in SetupStatus.Group.entries) {
        val inGroup = items.filter { it.group == g }
        if (inGroup.isEmpty()) continue
        println("\n${g.title}")
        for (i in inGroup) {
            val mark = when (i.state) { SetupStatus.State.OK -> "✓"; SetupStatus.State.TODO -> if (i.essential) "✗" else "!"; SetupStatus.State.INFO -> "·" }
            println("  $mark ${i.label.padEnd(20)}${i.text}")
        }
    }
    val todo = items.filter { it.state == SetupStatus.State.TODO }
    println()
    if (todo.isEmpty()) println("Everything is set up.")
    else {
        println("To do (✗ needed, ! for some work only):")
        todo.sortedBy { !it.essential }.forEach { i -> println("  - ${i.label}: " + listOfNotNull(i.cli, i.url).joinToString("  ·  ").ifEmpty { i.text }) }
        println("\n${no.agens.testlab.AppIdentity.SLUG} setup walks through these one at a time.")
    }
    rt.close()
    if (todo.any { it.essential }) 1 else 0
}
