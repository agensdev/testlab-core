plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    api(project(":device"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.keyring)
    implementation(libs.jna.platform)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":device")))
    // MockGitHub, shared with the mcp tests.
    testFixturesImplementation(libs.ktor.client.mock)
    testFixturesImplementation(libs.ktor.client.core)
}

// Profiles bundled into a branded build (-Ptestlab.profilesDir=…): each <id>/profile.json with its
// skills/ and plans/, plus profiles/index.txt (ids, the first is active on a fresh install) and
// <id>/files.txt (what ProfileStore unpacks). The open-source build bundles none.
val bundledProfiles = layout.buildDirectory.dir("generated/profiles")
val bundleProfiles = tasks.register("bundleProfiles") {
    val src = providers.gradleProperty("testlab.profilesDir").map { file(it) }
    inputs.property("dir", src.map { it.absolutePath }.orElse(""))
    src.orNull?.takeIf { it.isDirectory }?.let { inputs.dir(it) }
    val out = bundledProfiles
    outputs.dir(out)
    doLast {
        val root = out.get().asFile.resolve("profiles")
        root.deleteRecursively()
        val dir = src.orNull?.takeIf { it.isDirectory } ?: return@doLast
        val ids = dir.listFiles { f -> f.isDirectory && f.resolve("profile.json").isFile }.orEmpty().sortedBy { it.name }.map { it.name }
        // An explicit order (index.txt in the source folder) wins: its first id is the default.
        val order = dir.resolve("index.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }
        val sorted = (order.orEmpty().filter { it in ids } + ids.filter { order == null || it !in order })
        for (id in sorted) {
            val p = dir.resolve(id)
            val files = p.walkTopDown().filter { it.isFile && it.name != "profile.json" }.map { it.relativeTo(p).invariantSeparatorsPath }.sorted().toList()
            p.copyRecursively(root.resolve(id), overwrite = true)
            root.resolve("$id/files.txt").writeText(files.joinToString("\n"))
        }
        root.mkdirs()
        root.resolve("index.txt").writeText(sorted.joinToString("\n"))
    }
}
sourceSets.main { resources.srcDir(bundleProfiles) }
