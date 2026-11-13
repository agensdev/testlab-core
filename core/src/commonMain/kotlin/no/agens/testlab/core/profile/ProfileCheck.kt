package no.agens.testlab.core.profile

/** What is wrong with a profile, worded for the person who wrote it. Empty = usable. */
fun Profile.problems(): List<String> = buildList {
    if (!Profile.ID.matches(id)) add("id '$id': use 1–40 letters, digits, - or _")
    if (name.isBlank()) add("name is empty")
    if (platforms.isNotEmpty()) {
        for (k in platforms.keys) if (no.agens.testlab.core.Platform.of(k) == null) add("platforms.$k: not a platform (android or ios)")
        for (p in covers) {
            runCatching { variant(p) }
                .onSuccess { v -> v.oneProblems().forEach { add("platforms.${p.id}: $it") } }
                .onFailure { add("platforms.${p.id}: ${it.message?.lineSequence()?.firstOrNull()}") }
        }
        return@buildList
    }
    addAll(oneProblems())
}

private fun Profile.oneProblems(): List<String> = buildList {
    testRepos.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("testRepos: id '$it' is used twice") }
    for (t in testRepos) {
        if (!Regex("^[a-z0-9][a-z0-9_-]{0,39}$").matches(t.id)) add("testRepos.${t.id}: id must be lower-case letters, digits, - or _")
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(t.repo)) add("testRepos.${t.id}.repo '${t.repo}' is not owner/name")
        if (t.kind != "docs" && t.kind != "maestro") add("testRepos.${t.id}.kind must be docs or maestro, not '${t.kind}'")
        t.skills.filter { it.startsWith("/") || ".." in it.split('/') }.forEach { add("testRepos.${t.id}.skills: '$it' must be a path inside the repo") }
    }
    for ((pkg, app) in crashlytics.apps) {
        if (!Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(pkg)) add("crashlytics.apps: '$pkg' is not a package name")
        if (!Regex("^1:[0-9]+:android:[0-9a-f]+$").matches(app)) add("crashlytics.apps.$pkg: '$app' is not a Firebase Android app id (1:<project number>:android:<hash>)")
    }
    if (crashlytics.timeframe !in listOf("24h", "7d", "30d", "60d", "90d")) add("crashlytics.timeframe must be 24h, 7d, 30d, 60d or 90d, not '${crashlytics.timeframe}'")
    if (github.repo.isNotEmpty() && !Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(github.repo)) add("github.repo '${github.repo}' is not owner/name")
    val ids = builds.map { it.id.lowercase() }
    ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { add("builds: id '$it' is used twice") }
    for (b in builds) {
        if (!Regex("^[a-z0-9_-]+$").matches(b.id)) add("builds.${b.id}: id must be lower-case letters, digits, - or _")
        runCatching { Regex(b.artifact) }.onFailure { add("builds.${b.id}.artifact is not a valid regex: ${it.message}") }
        b.target?.let { if (it != "device" && it != "simulator") add("builds.${b.id}.target must be device or simulator, not '$it'") }
        if (b.target != null && platform != no.agens.testlab.core.Platform.IOS) add("builds.${b.id}.target is for iOS builds only")
    }
    if (builds.count { it.default } > 1) add("builds: only one build can be default")
    releases.buildId?.let { if (build(it) == null) add("releases.buildId '$it' is not one of the builds") }
    if (tracker.site.isNotEmpty() && ("/" in tracker.site || ":" in tracker.site)) add("tracker.site is a host name like example.atlassian.net, not a URL")
    tracker.projects.filterNot { Regex("^[A-Z][A-Z0-9_]+$").matches(it) }.forEach { add("tracker.projects: '$it' is not a Jira project key") }
    (tracker.queueLabels + tracker.requiredLabels + tracker.approvedLabel + tracker.onApproved.addLabels + tracker.onApproved.removeLabels)
        .filter { it.isBlank() || it.any(Char::isWhitespace) }.forEach { add("tracker: label '$it' has spaces (Jira labels can't)") }
    for ((name, d) in navigation.destinations) {
        if ((d.path == null) == (d.tab == null)) add("navigation.destinations.$name: give exactly one of path or tab")
        if (d.path != null && !d.path.startsWith("/") && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(d.path)) add("navigation.destinations.$name.path starts with / or is a URL")
    }
    if (navigation.destinations.values.any { it.path != null && it.path.startsWith("/") } && navigation.host.isBlank()) add("navigation.host is needed for paths that start with /")
    for (alias in navigation.aliases) if (alias.value !in navigation.destinations) add("navigation.aliases.${alias.key} → '${alias.value}' is not a destination")
    (autopilot.riskyWords + autopilot.leaveWords).forEach { w -> runCatching { Regex(w) }.onFailure { add("autopilot: '$w' is not a valid regex") } }
    accounts.fields.map { it.key }.filterNot { Regex("^[a-z0-9_]+$").matches(it) }.forEach { add("accounts.fields: key '$it' must be lower-case letters, digits and _") }
    accounts.defaults.filterNot { Regex("^[A-Za-z0-9_-]+$").matches(it) }.forEach { add("accounts.defaults: '$it' must be letters, digits, _ and -") }
    runCatching { Template.render(report.template, emptyMap(), emptyMap()) }.onFailure { add("report.template: ${it.message}") }
}
