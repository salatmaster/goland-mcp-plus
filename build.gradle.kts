import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.salatmaster"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        goland(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("org.jetbrains.plugins.go")
        testFramework(TestFrameworkType.Platform)
    }
    // compileOnly on purpose: bundling kotlinx-serialization into the plugin jar
    // collides with the MCP server plugin's classloader.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testCompileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("junit:junit:4.13.2")
    // Test-only: production code must not ship kotlin-reflect.
    testImplementation(kotlin("reflect"))
}

// IntelliJ Platform 2026.2 runs on Java 25; building against 21 risks
// missing or mis-resolved platform APIs.
kotlin { jvmToolchain(25) }

intellijPlatform {
    // The plugin contributes no settings UI yet; building searchable options
    // launches a headless IDE and costs about a minute per build.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // No until-build: pinning it would make the plugin uninstallable the moment
            // users update their IDE. The guardGoApi layer already turns an incompatible
            // com.goide API into a readable tool error naming the build, which is the
            // failure mode until-build was meant to prevent.
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    systemProperty("java.awt.headless", "true")

    // testData lives outside the resource directories, so Gradle would not otherwise
    // notice fixture edits and would report the previous run as still up to date.
    inputs.dir(layout.projectDirectory.dir("src/test/testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
