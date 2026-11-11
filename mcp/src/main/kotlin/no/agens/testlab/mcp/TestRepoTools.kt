package no.agens.testlab.mcp

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.LabRuntime

/** The profile's test repos: helper repos (guides, an E2E suite) read through Testlab instead of from a checkout. */
internal fun ToolBox.testRepoTools(rt: LabRuntime) {
    val repos = rt.testRepos

    tool(
        "lab_test_repos",
        """
        The project's test repos (profile.testRepos): helper repos the team tests with, such as test
        management guides or an E2E test suite. Testlab keeps a read-only copy of each (the branch's
        newest commit, fetched with the user's GitHub token, checked at most hourly; sync=true checks
        now). Lists each repo's id, what it is for, its commit, its skills (in lab_list_skills as
        <id>:<name>) and, for a maestro repo, how many flows it has. Read them with
        lab_test_repo_files / lab_test_repo_read / lab_test_repo_search / lab_maestro_flows; never
        clone them yourself, and never write to them.
        """,
        bool("sync", "check GitHub for newer commits now", false),
        readOnly = true,
    ) { a ->
        val list = repos.configured()
        if (list.isEmpty()) return@tool text("The profile «${rt.profile.name}» has no test repos (profile.json: testRepos, see docs/PROFILES.md).")
        val synced = list.map { repos.sync(it, force = a.bool("sync")) }
        text(buildJsonObject {
            putJsonArray("repos") {
                for (s in synced) addJsonObject {
                    val t = s.repo
                    put("id", t.id); put("repo", t.repo); put("kind", t.kind); t.ref?.let { put("ref", it) }
                    t.description?.let { put("for", it) }
                    s.state?.let { st -> put("commit", st.sha.take(10)); put("files", st.files); put("checked", java.time.Instant.ofEpochMilli(st.syncedAt).toString()) }
                    if (s.changed) put("updated", true)
                    s.problem?.let { put("problem", it) }
                    putJsonArray("skills") { repos.skills(t).forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } }
                    if (t.kind == "maestro") put("flows", repos.flows(t).size)
                }
            }
        }.encode())
    }

    tool(
        "lab_test_repo_files",
        "Files in a test repo (its id from lab_test_repos), under `path`, optionally only those matching `glob` (`*.md`, `tests/**/login*.yaml`).",
        str("repo", "the test repo's id"),
        str("path", "folder inside the repo; empty = all of it", ""),
        str("glob", "only files matching this glob", ""),
        int("limit", "at most this many", 300),
        readOnly = true,
    ) { a ->
        val t = repos.find(a.str("repo"))
        repos.sync(t)
        val list = repos.files(t, a.str("path"), a.strOrNull("glob"), a.int("limit").coerceIn(1, 5000))
        text(if (list.isEmpty()) "(no files)" else list.joinToString("\n"))
    }

    tool(
        "lab_test_repo_read",
        "Read a text file from a test repo: `lines` lines from line `from` (1-based).",
        str("repo", "the test repo's id"),
        str("path", "the file, relative to the repo"),
        int("from", "first line", 1),
        int("lines", "how many lines", 400),
        readOnly = true,
    ) { a ->
        val t = repos.find(a.str("repo"))
        repos.sync(t)
        text(repos.read(t, a.str("path"), a.int("from"), a.int("lines").coerceIn(1, 4000)))
    }

    tool(
        "lab_test_repo_search",
        "Search the test repos' text files for a regex (case-insensitive): path:line and the line. `repo` empty = every test repo; `glob` narrows the files.",
        str("query", "a regex, e.g. testinyId: TC-6845 or Kvitteringsnummer"),
        str("repo", "one test repo's id; empty = all", ""),
        str("glob", "only files matching this glob, e.g. *.md", ""),
        int("limit", "at most this many hits", 100),
        readOnly = true,
    ) { a ->
        val list = a.strOrNull("repo")?.let { listOf(repos.find(it)) } ?: repos.configured()
        if (list.isEmpty()) return@tool text("The profile has no test repos.")
        list.forEach { repos.sync(it) }
        val hits = repos.search(list, a.str("query"), a.strOrNull("glob"), a.int("limit").coerceIn(1, 1000))
        text(if (hits.isEmpty()) "no matches" else hits.joinToString("\n") { "${it.repo}:${it.path}:${it.line}: ${it.text}" })
    }

    tool(
        "lab_maestro_flows",
        """
        The Maestro flows in a test repo of kind maestro: path, name, test-case id (properties.testinyId)
        and tags. Filter by `query` (in the path or name), `tag`, or `test_case` (e.g. TC-6845), to find the
        automated test behind a test case, or which test cases a screen's flows cover. Read a flow with
        lab_test_repo_read.
        """,
        str("repo", "the maestro test repo's id; empty = the first one", ""),
        str("query", "text in the path or name", ""),
        str("tag", "only flows with this tag (regtest, smoke…)", ""),
        str("test_case", "only the flows for this test-case id", ""),
        int("limit", "at most this many", 200),
        readOnly = true,
    ) { a ->
        val t = a.strOrNull("repo")?.let { repos.find(it) } ?: repos.configured().firstOrNull { it.kind == "maestro" }
            ?: return@tool text("The profile has no test repo of kind maestro.")
        repos.sync(t)
        val q = a.strOrNull("query")?.lowercase()
        val flows = repos.flows(t).filter { f ->
            (q == null || q in f.path.lowercase() || q in f.name.orEmpty().lowercase()) &&
                (a.strOrNull("tag")?.let { it in f.tags } ?: true) &&
                (a.strOrNull("test_case")?.let { it.equals(f.testCase, ignoreCase = true) } ?: true)
        }
        text(buildJsonObject {
            put("repo", t.id); put("count", flows.size)
            putJsonArray("flows") { flows.take(a.int("limit").coerceIn(1, 2000)).forEach { f -> addJsonObject {
                put("path", f.path); f.name?.let { put("name", it) }; f.testCase?.let { put("test_case", it) }
                if (f.tags.isNotEmpty()) putJsonArray("tags") { f.tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            } } }
        }.encode())
    }
}
