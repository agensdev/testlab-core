plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // FakeHsDaemon / FakePhone, shared with the services and mcp tests.
    testFixturesImplementation(libs.serialization.json)
}
