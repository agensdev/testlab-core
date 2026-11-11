rootProject.name = "testlab"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

// core: pure logic (KMP, commonMain). device/services/mcp: JVM integrations (mcp also serves the
// local web pages). cli: the `testlab` command, the server and the MCP stdio bridge.
include(":core", ":device", ":services", ":mcp", ":cli")
