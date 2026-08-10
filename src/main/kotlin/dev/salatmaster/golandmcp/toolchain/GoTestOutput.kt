package dev.salatmaster.golandmcp.toolchain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GoTestCase(
    val pkg: String,
    val name: String,
    val passed: Boolean,
    val skipped: Boolean,
    val elapsedSeconds: Double,
    /** Captured output; only meaningful for failures. */
    val output: String,
)

data class GoTestRun(
    val cases: List<GoTestCase>,
    val passedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    /** Packages that failed to build, keyed by package with the compiler message. */
    val buildErrors: List<String>,
)

/**
 * Parses the event stream produced by `go test -json`.
 *
 * Returning the raw log would routinely cost tens of thousands of tokens and bury the one
 * thing that matters. This keeps per-test output attached to the test that produced it, so
 * a caller can report only the failures.
 */
object GoTestOutputParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(stdout: String): GoTestRun {
        val outputs = LinkedHashMap<String, StringBuilder>()
        val packageOutput = LinkedHashMap<String, StringBuilder>()
        val failedPackages = LinkedHashSet<String>()
        val cases = LinkedHashMap<String, GoTestCase>()
        val buildErrors = mutableListOf<String>()

        for (line in stdout.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith("{")) {
                // Build failures are printed as plain text before the JSON stream starts.
                buildErrors += trimmed
                continue
            }

            val event = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }
                .getOrNull() ?: continue

            val action = event.string("Action") ?: continue
            val pkg = event.string("Package").orEmpty()

            // Events with no Test are about the package itself, and that is where a build
            // failure arrives. Dropping them made a package that never compiled look like a
            // package with no tests -- a passing-looking answer to a run that never happened.
            val test = event.string("Test")
            if (test == null) {
                when (action) {
                    "output", "build-output" ->
                        packageOutput.getOrPut(pkg) { StringBuilder() }
                            .append(event.string("Output").orEmpty())

                    "build-fail" -> failedPackages += pkg
                    "fail" -> failedPackages += pkg
                }
                continue
            }
            val key = "$pkg.$test"

            when (action) {
                "output" -> outputs.getOrPut(key) { StringBuilder() }
                    .append(event.string("Output").orEmpty())

                "pass", "fail", "skip" -> {
                    cases[key] = GoTestCase(
                        pkg = pkg,
                        name = test,
                        passed = action == "pass",
                        skipped = action == "skip",
                        elapsedSeconds = event["Elapsed"]?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?: 0.0,
                        output = outputs[key]?.toString()?.trim().orEmpty(),
                    )
                }
            }
        }

        val all = cases.values.toList()

        // A package that failed while contributing no test result did not run its tests; its
        // own output is the compiler or toolchain message explaining why.
        for (pkg in failedPackages) {
            if (all.any { it.pkg == pkg }) continue
            packageOutput[pkg]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.lines()
                ?.forEach { buildErrors += it.trim() }
        }

        return GoTestRun(
            cases = all,
            passedCount = all.count { it.passed },
            failedCount = all.count { !it.passed && !it.skipped },
            skippedCount = all.count { it.skipped },
            buildErrors = buildErrors.filter { it.isNotEmpty() }.distinct(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content
}
