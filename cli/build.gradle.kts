plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("no.agens.testlab.cli.MainKt")
    applicationName = providers.gradleProperty("testlab.slug").get()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":device"))
    implementation(project(":services"))
    implementation(project(":mcp"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // Logs go to stderr (simplelogger.properties): in --mcp-stdio mode stdout carries the protocol.
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
}

// --- The headless CLI distribution (`packageCli`): the CLI's jars, a jlink'd Java runtime and
// launchers in bin/, as <slug>-cli-<version>-<os>-<arch>.tar.gz (.zip on Windows). No window code,
// so it runs on a server; `service install` makes it a background service (docs/SERVICE.md).

val cliSlug = providers.gradleProperty("testlab.slug").get()
val cliName = providers.gradleProperty("testlab.name").get()
val cliVersion = providers.gradleProperty("testlab.version").get()
val hostOs = System.getProperty("os.name").lowercase().let { n -> when { "mac" in n -> "macos"; "win" in n -> "windows"; else -> "linux" } }
val hostArch = System.getProperty("os.arch").lowercase().let { a -> if (a == "aarch64" || a == "arm64") "arm64" else "x64" }
val cliBase = "$cliSlug-cli-$cliVersion-$hostOs-$hostArch"
// jdeps over the CLI's jars, plus TLS (jdk.crypto.ec), JNDI for the keyring and extra charsets.
val cliModules = listOf(
    "java.base", "java.desktop", "java.instrument", "java.logging", "java.management", "java.net.http",
    "jdk.net", "jdk.security.auth", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "jdk.charsets", "jdk.zipfs",
)
val jdkHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.map { it.metadata.installationPath }
val cliRoot = layout.buildDirectory.dir("cli-dist/$cliSlug")

val cliRuntime by tasks.registering(Exec::class) {
    description = "A trimmed Java runtime for the CLI distribution (jlink)."
    val out = layout.buildDirectory.dir("cli-runtime")
    outputs.dir(out)
    inputs.property("modules", cliModules)
    doFirst { out.get().asFile.deleteRecursively() }
    executable = jdkHome.get().file("bin/jlink" + if (hostOs == "windows") ".exe" else "").asFile.path
    args("--add-modules", cliModules.joinToString(","), "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6", "--output", out.get().asFile.path)
}

val cliDist by tasks.registering(Sync::class) {
    description = "The CLI distribution folder: bin/, lib/, runtime/."
    dependsOn(cliRuntime)
    into(cliRoot)
    from(tasks.named("installDist").map { (it as Sync).destinationDir.resolve("lib") }) { into("lib") }
    from(layout.buildDirectory.dir("cli-runtime")) { into("runtime") }
    from(rootProject.file("LICENSE")); from(rootProject.file("NOTICE"))
    val jvm = "-Xmx1g -Dfile.encoding=UTF-8"
    val main = "no.agens.testlab.cli.MainKt"
    // Locals, not the script's vals: the configuration cache can't serialize the script object.
    val rootDir = cliRoot.get().asFile
    val slug = cliSlug
    val name = cliName
    doLast {
        val bin = rootDir.resolve("bin").apply { mkdirs() }
        bin.resolve(slug).apply {
            writeText(
                """
                |#!/bin/sh
                |# $name CLI (headless), on the bundled Java runtime. Symlinks to this file work.
                |p="${'$'}0"
                |while [ -h "${'$'}p" ]; do l=${'$'}(readlink "${'$'}p"); case "${'$'}l" in /*) p="${'$'}l" ;; *) p="${'$'}(dirname "${'$'}p")/${'$'}l" ;; esac; done
                |APP_HOME=${'$'}(cd "${'$'}(dirname "${'$'}p")/.." && pwd -P)
                |exec "${'$'}APP_HOME/runtime/bin/java" $jvm -Dtestlab.home="${'$'}APP_HOME" -cp "${'$'}APP_HOME/lib/*" $main "${'$'}@"
                |""".trimMargin(),
            )
            setExecutable(true, false)
        }
        bin.resolve("$slug.bat").writeText(
            listOf(
                "@echo off",
                "rem $name CLI (headless), on the bundled Java runtime.",
                "setlocal",
                "for %%I in (\"%~dp0..\") do set \"APP_HOME=%%~fI\"",
                "\"%APP_HOME%\\runtime\\bin\\java.exe\" $jvm \"-Dtestlab.home=%APP_HOME%\" -cp \"%APP_HOME%\\lib\\*\" $main %*",
                "exit /b %ERRORLEVEL%",
            ).joinToString("\r\n", postfix = "\r\n"),
        )
    }
}

val packageCli by tasks.registering(if (hostOs == "windows") Zip::class else Tar::class) {
    description = "The CLI distribution as an archive for this OS ($cliBase)."
    dependsOn(cliDist)
    destinationDirectory.set(layout.buildDirectory.dir("cli-package"))
    archiveFileName.set(cliBase + if (hostOs == "windows") ".zip" else ".tar.gz")
    if (this is Tar) compression = Compression.GZIP
    from(cliRoot) { into(cliSlug) }
    // Keep the launchers and the runtime's binaries executable in the tar.
    eachFile { if (path.contains("/bin/") || path.contains("/lib/jspawnhelper")) permissions { unix("rwxr-xr-x") } }
}

// --- The Claude Code plugin (claude-plugin/) inside the CLI jar, so `claude-plugin` installs it
// from this computer: a private repo can't be added as a marketplace without git credentials.
val claudePluginRes by tasks.registering(Sync::class) {
    description = "The Claude Code plugin and its marketplace file as resources, with an index."
    val out = layout.buildDirectory.dir("generated/claude-plugin-res")
    into(out)
    from(rootProject.file("claude-plugin")) {
        into("claude-plugin/claude-plugin")
        exclude("tests/**", ".gitignore", ".claude-plugin/types/**", "tsconfig.json", "node_modules/**")
    }
    from(rootProject.file(".claude-plugin/marketplace.json")) { into("claude-plugin/.claude-plugin") }
    val root = out.get().asFile.resolve("claude-plugin")
    doLast {
        val files = root.walkTopDown().filter { it.isFile && it.name != "index.txt" }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        root.resolve("index.txt").writeText(files.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main { resources.srcDir(claudePluginRes) }
