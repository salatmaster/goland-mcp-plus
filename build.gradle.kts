import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.12.0"
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

kotlin { jvmToolchain(21) }

intellijPlatform {
    // The plugin contributes no settings UI yet; building searchable options
    // launches a headless IDE and costs about a minute per build.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

tasks.test {
    systemProperty("java.awt.headless", "true")
}
