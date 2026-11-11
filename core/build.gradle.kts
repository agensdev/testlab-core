plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// AppIdentity is generated from gradle.properties so the app can be renamed in one place.
val identityDir = layout.buildDirectory.dir("generated/identity/kotlin")
val generateAppIdentity = tasks.register("generateAppIdentity") {
    val id = providers.gradleProperty("testlab.id")
    val name = providers.gradleProperty("testlab.name")
    val slug = providers.gradleProperty("testlab.slug")
    val vendor = providers.gradleProperty("testlab.vendor")
    val version = providers.gradleProperty("testlab.version")
    val repo = providers.gradleProperty("testlab.repo")
    // Which commit this is (short hash and commit date): every local build says the same version.
    val build = providers.gradleProperty("testlab.build").orElse(
        providers.exec { commandLine("git", "log", "-1", "--format=%h %cs"); isIgnoreExitValue = true }.standardOutput.asText.map { it.trim().ifEmpty { "dev" } },
    )
    inputs.property("repo", repo)
    inputs.property("build", build)
    inputs.property("id", id)
    inputs.property("name", name)
    inputs.property("slug", slug)
    inputs.property("vendor", vendor)
    inputs.property("version", version)
    val out = identityDir
    outputs.dir(out)
    doLast {
        val file = out.get().file("no/agens/testlab/AppIdentity.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package no.agens.testlab
            |
            |/** Generated from gradle.properties (testlab.*) — do not edit. */
            |object AppIdentity {
            |    /** Reverse-DNS id: macOS bundle id and keychain service name. */
            |    const val ID: String = "${id.get()}"
            |    /** Display name, also the data folder name on macOS and Windows. */
            |    const val NAME: String = "${name.get()}"
            |    /** Machine name: MCP server name, Linux package and data folder. */
            |    const val SLUG: String = "${slug.get()}"
            |    const val VENDOR: String = "${vendor.get()}"
            |    const val VERSION: String = "${version.get()}"
            |    /** The commit it was built from (short hash and date), or "dev". */
            |    const val BUILD: String = "${build.get()}"
            |    /** GitHub repo (owner/name) whose Releases the update check reads. */
            |    const val REPO: String = "${repo.get()}"
            |    /** "TEST" on a phone keypad. */
            |    const val MCP_DEFAULT_PORT: Int = 8378
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppIdentity)
            dependencies {
                implementation(libs.serialization.json)
                implementation(libs.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
            }
        }
    }
}
