plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    implementation(project(":device"))
    implementation(project(":services"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.mcp.server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mcp.client)
    testImplementation(testFixtures(project(":device")))
    testImplementation(testFixtures(project(":services")))
    testImplementation(libs.ktor.client.mock)
}

// The guide is written once, in docs/GUIDE.md (GitHub shows it); the local server serves the same text at /ui/guide.
tasks.processResources {
    from(rootProject.file("docs/GUIDE.md")) { into("guide") }
}
