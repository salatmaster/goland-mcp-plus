import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    // Applied only to render one changelog section into <change-notes>. The file itself is
    // cut by .github/scripts/cut-changelog.sh, and the plugin's own patchChangelog is
    // disabled below so it cannot cut it a second way.
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "io.github.salatmaster"
// Released versions come from the git tag: the release workflow derives PLUGIN_VERSION
// from it. The gradle.properties value is only the fallback for local and CI builds, so
// there is one place a version is ever declared, and it is the tag.
version = providers.environmentVariable("PLUGIN_VERSION")
    .orElse(providers.gradleProperty("pluginVersion"))
    .get()

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

// Read-only use of the changelog, and the version has to be told to it explicitly: the
// extension defaults to the project version, which is only ever right by coincidence here,
// since ours comes from an environment variable the workflow sets.
changelog {
    version = project.version.toString()
}

// Applying the changelog plugin also puts patchChangelog into publishPlugin's task graph,
// and it is not theoretical: it ran during the 0.2.3 release. That task rewrites
// CHANGELOG.md, a tracked file, in the middle of publishing. It did no harm only because
// the workflow commits the changelog before it publishes, so the rewrite was thrown away
// with the runner -- but a failure inside it would fail publishPlugin after the tag and the
// GitHub release already exist, which is the one outcome the order of the release steps is
// built to prevent. Cutting the changelog belongs to .github/scripts/cut-changelog.sh, and
// to nothing else.
tasks.named("patchChangelog") { enabled = false }

intellijPlatform {
    // The one settings page this plugin contributes is a read-only usage table with nothing
    // to search for, and building searchable options launches a headless IDE for about a
    // minute on every build.
    buildSearchableOptions = false

    // All three read from the environment, so they are inert locally and configured by
    // the release workflow. Signing is what the Marketplace requires of an update to an
    // already-signed plugin; publishing needs a token that must never live in the repo.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides { recommended() }
    }

    pluginConfiguration {
        // <change-notes> is what the Marketplace shows as "What's new" for a version, and
        // what the Plugins dialog shows when it offers an update. It was never set, so every
        // release through 0.2.2 published a blank one.
        //
        // The section read is the one for the version being built, not [Unreleased]: the
        // release workflow cuts the changelog before it builds, so by then the entry has
        // already moved under its own version heading and [Unreleased] is empty. That is why
        // the changelog plugin's own convention, which wires change notes to [Unreleased], is
        // not used. The fallback is for a local build of a version the changelog has not
        // heard of, which is every local build.
        changeNotes = provider {
            with(changelog) {
                val item = getOrNull(project.version.toString()) ?: getUnreleased()
                renderItem(item.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
            }
        }

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

    // The agent plugin is read from the repository for the same reason, and with the same
    // consequence: without this, breaking a manifest leaves the suite reporting the
    // previous run as still valid.
    inputs.dir(layout.projectDirectory.dir("plugins"))
        .withPropertyName("agentPlugin")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        layout.projectDirectory.file(".claude-plugin/marketplace.json"),
        layout.projectDirectory.file(".agents/plugins/marketplace.json"),
    )
        .withPropertyName("marketplaces")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
