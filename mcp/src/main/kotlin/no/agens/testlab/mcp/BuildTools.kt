package no.agens.testlab.mcp

import kotlinx.serialization.json.JsonNull
import no.agens.testlab.AppIdentity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.agens.testlab.mcp.Param.Companion.PLATFORM
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.builds.BuildType
import no.agens.testlab.services.builds.Builds
import no.agens.testlab.services.builds.InstallOutcome
import no.agens.testlab.services.builds.InstallProblem
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.NotApproved

private val TYPE = str("type", "build type id from the active profile (lab_status lists them); empty = the default (PR builds; on a real iPhone or this Mac the first device build)", "")

private fun outcomeJson(o: InstallOutcome, serial: String) = buildJsonObject {
    o.build?.let { b ->
        put("branch", b.branch)
        put("head_sha", b.run.headSha.take(10))
        b.pr?.let { put("pr", it.number) }
        put("latest_commit", b.isLatest)
    }
    o.release?.let { put("tag", it.tag) }
    put("serial", serial)
    put("apk", o.apk.path)
    put("package", o.info.packageName)
    put("versionCode", o.info.versionCode?.toString() ?: "")
    put("versionName", o.info.versionName ?: "")
    put("install", o.result.output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "")
    o.downgrade?.let { put("downgrade", it) }
    if (!o.result.ok) {
        o.result.problem?.let { put("problem", it.code) }
        put("advice", o.result.advice + if (o.result.problem == InstallProblem.SIGNATURE) ": handsets_uninstall_app(\"${o.info.packageName}\"), then this tool again" else "")
    }
}

private fun stillInstalling(what: String, serial: String) =
    "Still installing $what on $serial (large download or a slow adb link): it goes on in the background. " +
        "Call this tool again with the same arguments for the result; it does not install twice."

internal fun ToolBox.buildTools(rt: LabRuntime) {
    val slow = SlowCalls()

    tool(
        "handsets_list_branch_builds",
        """
        CI build for a BRANCH or PR number: the latest successful run of the build type's workflow and
        its artifacts. Use it to see whether a PR-head APK exists before handsets_install_branch_build.
        Returns JSON {branch, run_id, head_sha, created, artifacts[{id, name, bytes, expired}], pr,
        latest_commit (whether the build is from the PR's newest commit), building (a newer run still
        going), run_url}.
        """,
        str("branch_or_pr", "branch name, PR number, or PR URL"),
        TYPE,
        str("serial", "a device whose platform's profile to use (an iOS simulator: the iOS profile's repo); empty = the device in use", ""),
        readOnly = true,
    ) { a ->
        val p = rt.builds.profileOf(a.strOrNull("serial"))
        val b = rt.builds.branchBuild(a.str("branch_or_pr"), rt.builds.type(a.str("type"), p), p)
        val arts = runCatching { rt.builds.github(p).runArtifacts(b.run.id) }.getOrDefault(emptyList())
        text(buildJsonObject {
            put("branch", b.branch)
            put("run_id", b.run.id)
            put("head_sha", b.run.headSha.take(10))
            put("created", b.run.createdAt)
            putJsonArray("artifacts") { for (x in arts) addJsonObject { put("id", x.id); put("name", x.name); put("bytes", x.bytes); put("expired", x.expired) } }
            b.pr?.let { pr -> putJsonObject("pr") { put("number", pr.number); put("title", pr.title); put("draft", pr.draft); put("head_sha", pr.headSha.take(10)) } }
            put("latest_commit", b.isLatest)
            if (!b.isLatest) put("stale", "the PR has newer commits than this build (${b.pr?.headSha?.take(7)}): wait for or start a new build")
            rt.builds.behindDefault(b.run.headSha, p)?.let { (ahead, behind) -> put("ahead_of_default_branch", ahead); put("behind_default_branch", behind) }
            // (put returns the previous value, so `?.let { put… } ?: put(null)` would always end at null)
            val building = b.building
            if (building != null) putJsonObject("building") { put("run_id", building.id); put("status", building.status); put("url", building.htmlUrl) } else put("building", JsonNull)
            put("run_url", b.run.htmlUrl)
        }.encode())
    }

    tool(
        "handsets_install_branch_build",
        """
        Download the CI build for a BRANCH or PR number (the GitHub Actions artifact) and install it
        on the device, keeping app data and login: an APK on Android (`adb install -r -d`), the
        simulator build's .app on an iOS Simulator (`simctl install`; the iOS profile's repo and
        build types), the device build's .ipa on a real iPhone or iPad (`devicectl`) or on this Mac
        (serial `mac`; Apple Silicon runs iOS apps). This is how a devtest gets the PR-head build (releases are handsets_install_build).
        Returns JSON {branch, head_sha, pr, latest_commit, apk, package, versionCode, versionName,
        install} and, when the install fails, problem and advice. About 110 MB the first time, then
        cached; 20–60 s, more over Wi-Fi adb. A call answers within 45 s: if the install is still
        going it says so, and calling again with the same arguments returns its result.
        """,
        str("branch_or_pr", "branch name, PR number, or PR URL"),
        TYPE,
        SERIAL,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val ref = a.str("branch_or_pr")
        val type = rt.builds.type(a.str("type"), rt.builds.profileOf(serial), realDevice = rt.realIos(serial))
        text(slow.run("branch|$ref|${type.id}|${serial}", stillInstalling("the build of $ref", serial)) {
            outcomeJson(rt.builds.installBranchBuild(ref, type, serial), serial).encode()
        })
    }

    tool(
        "handsets_control_build",
        """
        The control to compare a PR's build with: the PR's merge-base with its base branch (the PR's
        own commits are then the only difference), whether CI built that exact commit, how far the PR
        is behind its base, and the newest release: changed_since_release lists the PR's files that
        changed between that release and the merge base (empty = the control also shows what users have
        for those files). install=true installs the merge-base build on the device. Identify builds by
        commit, not versionCode: a control built later has a higher versionCode.
        """,
        str("pr", "PR number or URL"),
        TYPE,
        Param.bool("install", "install the merge-base build", false),
        SERIAL,
    ) { a ->
        val serial = if (a.bool("install")) rt.serialFor(a.serial) else a.serial
        val p = rt.builds.profileOf(serial)
        val type = rt.builds.type(a.str("type"), p)
        val c = rt.builds.control(a.str("pr"), type, p)
        val info = buildJsonObject {
            put("pr", c.pr.number); put("base", c.baseRef); put("merge_base", c.mergeBase.take(10)); put("pr_behind_base", c.behindBy)
            val run = c.run
            if (run != null) putJsonObject("build") { put("run", run.runNumber); put("artifact", c.artifact?.name ?: ""); put("url", run.htmlUrl) }
            else put("build", "no CI build of the merge-base commit: CI usually builds branch heads only. Options: the newest release as the control (handsets_install_build), or a build of ${c.baseRef} (handsets_start_branch_build), which is ${c.baseRef}'s head, not the merge base: say which in the report")
            c.releaseTag?.let { put("release", it) }
            c.changedSinceRelease?.let { files -> putJsonArray("changed_since_release") { files.forEach { add(JsonPrimitive(it)) } } }
        }
        if (!a.bool("install") || c.run == null) return@tool text(info.encode())
        val o = rt.builds.installControl(c, type, serial!!)
        text(kotlinx.serialization.json.JsonObject(info + ("installed" to outcomeJson(o, serial))).encode())
    }

    tool(
        "handsets_install_apk",
        """
        Install an APK file from this computer on the device (a local or working-tree build), keeping app
        data (`adb install -r -d`; an older build over a newer one is uninstalled first, after a yes).
        The install is recorded as source "file" for handsets_installed_builds.
        """,
        str("path", "absolute path of the .apk on this computer"),
        SERIAL,
    ) { a ->
        val f = java.io.File(a.str("path"))
        if (!f.isFile || !f.name.endsWith(".apk")) throw BadArgument("${f.path} is not an .apk file on this computer")
        val serial = rt.serialFor(a.serial)
        if (rt.platformOf(serial) != no.agens.testlab.core.Platform.ANDROID) throw BadArgument("handsets_install_apk is for Android devices")
        text(slow.run("apk|${f.absolutePath}|$serial", stillInstalling(f.name, serial)) { outcomeJson(rt.builds.installFile(f, serial), serial).encode() })
    }

    tool(
        "handsets_list_builds",
        "List installable builds published as the repo's GitHub Releases: tag, date and asset, newest first. Install one with handsets_install_build.",
        PLATFORM,
        readOnly = true,
    ) {
        val rs = rt.builds.releases()
        text(if (rs.isEmpty()) "(no releases with an APK in ${rt.profile.github.repo})" else rs.joinToString("\n") { (r, asset) ->
            "%12s  %s  %s".format(r.tag, r.publishedAt?.take(10) ?: "", asset.name)
        })
    }

    tool(
        "handsets_install_build",
        """
        Download a build from the repo's GitHub Releases and install it on the device. tag="latest"
        installs the newest release that has an APK; or pass a tag (see handsets_list_builds). If it
        fails with a signature mismatch, the result says so: uninstall the installed copy, then retry.
        A call answers within 45 s; if the install is still going, call again with the same arguments.
        """,
        str("tag", "release tag, or latest", "latest"),
        SERIAL,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val tag = a.str("tag")
        text(slow.run("release|$tag|${serial}", stillInstalling("release $tag", serial)) {
            outcomeJson(rt.builds.installRelease(tag, serial), serial).encode()
        })
    }

    tool(
        "handsets_installed_builds",
        """
        Which builds ${AppIdentity.NAME} installed on the device and are still there: package, version,
        branch, commit or release tag, when. The phone itself can't tell builds of one package apart (they
        share one package), so use this for the «Bygg» line of a devtest report.
        """,
        SERIAL,
        readOnly = true,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val list = rt.builds.installedOn(serial)
        text(buildJsonObject {
            put("serial", serial)
            putJsonArray("builds") {
                for (b in list) addJsonObject {
                    put("package", b.packageName); put("versionName", b.versionName ?: ""); put("versionCode", b.versionCode?.toString() ?: "")
                    put("source", b.source); b.branch?.let { put("branch", it) }; b.headSha?.let { put("head_sha", it.take(10)) }
                    b.tag?.let { put("tag", it) }; b.artifactId?.let { put("artifact_id", it) }; put("installed_at", b.installedAt)
                }
            }
        }.encode())
    }

    tool(
        "handsets_start_branch_build",
        """
        Start the build type's workflow on a branch or PR by hand (workflow_dispatch), for a branch
        the workflow skips (no matching prefix, draft PR) or an expired build. It takes a CI runner
        (the user approves it first: on the approvals page, with `${AppIdentity.SLUG} approve`, or in this client's dialog). `inputs` are the
        workflow_dispatch inputs (JSON object); omitted ones use the profile's defaults. Returns the
        run's URL; follow it with handsets_list_branch_builds (field building) and install with
        handsets_install_branch_build.
        """,
        str("branch_or_pr", "branch name, PR number, or PR URL"),
        TYPE,
        Param("inputs", "object", "workflow_dispatch inputs, e.g. {\"environment\": \"stage\"}", kotlinx.serialization.json.JsonObject(emptyMap())),
        PLATFORM,
    ) { a ->
        val type = rt.builds.type(a.str("type"))
        val ref = a.str("branch_or_pr")
        val given = (a.json("inputs") as? kotlinx.serialization.json.JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
        val inputs = rt.builds.dispatchDefaults(type) + given
        if (!rt.approver.approve("Start a ${type.label} build", "Run ${type.workflowFile ?: type.label} on $ref" + (if (inputs.isNotEmpty()) " (${inputs.entries.joinToString { "${it.key}=${it.value}" }})" else "") + " in ${rt.profile.github.repo}? ${rt.builds.startBuildCost()}.")) {
            throw NotApproved("not approved: starting a build needs a yes (`${AppIdentity.SLUG} open approvals`, `${AppIdentity.SLUG} approve`, or this client's dialog)")
        }
        val run = rt.builds.startBuild(ref, type, inputs)
        text(run?.let { "started run ${it.runNumber} on ${it.headBranch}: ${it.htmlUrl}" } ?: "started; GitHub has not listed the run yet: check handsets_list_branch_builds in a minute")
    }
}
