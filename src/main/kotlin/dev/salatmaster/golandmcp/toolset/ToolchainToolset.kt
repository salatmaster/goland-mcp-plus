package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.metrics.tracked
import dev.salatmaster.golandmcp.toolchain.GoCommandResult
import dev.salatmaster.golandmcp.toolchain.GoDiagnosticsParser
import dev.salatmaster.golandmcp.toolchain.GoTestOutputParser
import dev.salatmaster.golandmcp.toolchain.GoToolchainRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GoFailedTest(
    val pkg: String,
    val name: String,
    val elapsedSeconds: Double,
    /** Output of this test only, trimmed to the configured budget. */
    val output: String,
)

@Serializable
data class GoTestResult(
    val command: String,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    /** Only failures are listed; passing tests are counted, not described. */
    val failures: List<GoFailedTest>,
    val buildErrors: List<String>,
    val timedOut: Boolean,
    val hint: String,
)

@Serializable
data class GoDiagnosticEntry(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
)

@Serializable
data class GoCheckResult(
    val command: String,
    val ok: Boolean,
    val diagnostics: List<GoDiagnosticEntry>,
    /** Lines carrying no position, such as linker or toolchain messages. */
    val notes: List<String>,
    val timedOut: Boolean,
)

@Serializable
data class GoModResult(
    val command: String,
    val ok: Boolean,
    val output: String,
    val timedOut: Boolean,
)

class ToolchainToolset : McpToolset {

    private val runner = GoToolchainRunner()

    @McpTool
    @McpDescription(
        "Run Go tests and return only what failed: each failure with its package, name and " +
            "its own output. Passing tests are counted, not printed. Prefer this over running " +
            "'go test' in a terminal, where a large suite floods the context with output that " +
            "buries the failure.",
    )
    suspend fun go_test(
        @McpDescription("Package pattern, e.g. './...' or './internal/store'")
        packagePattern: String,
        @McpDescription("Regular expression selecting test names, or empty for all")
        runPattern: String,
        @McpDescription("Timeout in milliseconds")
        timeoutMs: Int,
    ): GoTestResult =
        tracked("go_test") {
            test(currentCoroutineContext().project, packagePattern, runPattern, timeoutMs)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun test(
        project: Project,
        packagePattern: String,
        runPattern: String,
        timeoutMs: Int,
    ): GoTestResult {
        val arguments = buildList {
            add("test")
            add("-json")
            if (runPattern.isNotBlank()) {
                add("-run")
                add(runPattern)
            }
            add(packagePattern.ifBlank { "./..." })
        }

        val result = execute(project, arguments, timeoutMs)
        val run = GoTestOutputParser.parse(result.stdout + "\n" + result.stderr)

        val failures = run.cases
            .filter { !it.passed && !it.skipped }
            .map {
                GoFailedTest(
                    pkg = it.pkg,
                    name = it.name,
                    elapsedSeconds = it.elapsedSeconds,
                    output = it.output.take(MAX_FAILURE_OUTPUT),
                )
            }

        return GoTestResult(
            command = result.command,
            passed = run.passedCount,
            failed = run.failedCount,
            skipped = run.skippedCount,
            failures = failures,
            buildErrors = run.buildErrors.take(MAX_BUILD_ERRORS),
            timedOut = result.timedOut,
            hint = when {
                result.timedOut -> "The run hit the timeout; raise timeoutMs or narrow packagePattern."
                run.buildErrors.isNotEmpty() -> "The package failed to build, so tests did not run."
                run.failedCount == 0 && run.passedCount == 0 -> "No tests matched. Check packagePattern and runPattern."
                else -> ""
            },
        )
    }

    @McpTool
    @McpDescription(
        "Compile Go packages and return compilation errors as a structured list of file, " +
            "line, column and message, rather than raw compiler output.",
    )
    suspend fun go_build_check(
        @McpDescription("Package pattern, e.g. './...'")
        packagePattern: String,
        @McpDescription("Timeout in milliseconds")
        timeoutMs: Int,
    ): GoCheckResult =
        tracked("go_build_check") {
            buildCheck(currentCoroutineContext().project, packagePattern, timeoutMs)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun buildCheck(
        project: Project,
        packagePattern: String,
        timeoutMs: Int,
    ): GoCheckResult {
        // `go build -o /dev/null` type-checks without leaving binaries behind.
        val result = execute(
            project,
            listOf("build", "-o", nullDevice(), packagePattern.ifBlank { "./..." }),
            timeoutMs,
        )
        return result.toCheckResult()
    }

    @McpTool
    @McpDescription(
        "Run go vet and return its findings as a structured list of file, line, column and " +
            "message. Catches mistakes the compiler accepts, such as printf argument " +
            "mismatches and unreachable code.",
    )
    suspend fun go_vet(
        @McpDescription("Package pattern, e.g. './...'")
        packagePattern: String,
        @McpDescription("Timeout in milliseconds")
        timeoutMs: Int,
    ): GoCheckResult =
        tracked("go_vet") { vet(currentCoroutineContext().project, packagePattern, timeoutMs) }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun vet(
        project: Project,
        packagePattern: String,
        timeoutMs: Int,
    ): GoCheckResult =
        execute(project, listOf("vet", packagePattern.ifBlank { "./..." }), timeoutMs)
            .toCheckResult()

    @McpTool
    @McpDescription(
        "Run a go mod subcommand: tidy, download, verify, why or graph. Uses the SDK " +
            "configured in the IDE, so it sees the same module cache and proxy settings as " +
            "the editor.",
    )
    suspend fun go_mod(
        @McpDescription("Subcommand: tidy, download, verify, why or graph")
        subcommand: String,
        @McpDescription("Extra arguments, e.g. a module path for 'why'; may be empty")
        arguments: List<String>,
        @McpDescription("Timeout in milliseconds")
        timeoutMs: Int,
    ): GoModResult =
        tracked("go_mod") {
            mod(currentCoroutineContext().project, subcommand, arguments, timeoutMs)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun mod(
        project: Project,
        subcommand: String,
        arguments: List<String>,
        timeoutMs: Int,
    ): GoModResult {
        // An allowlist: `go mod edit` rewrites go.mod in ways the caller cannot review here,
        // and the read-only subcommands cover what an agent actually needs.
        if (subcommand !in ALLOWED_MOD_SUBCOMMANDS) {
            mcpFail(
                "Unsupported go mod subcommand '$subcommand'. " +
                    "Allowed: ${ALLOWED_MOD_SUBCOMMANDS.joinToString(", ")}.",
            )
        }

        val result = execute(project, listOf("mod", subcommand) + arguments, timeoutMs)
        return GoModResult(
            command = result.command,
            ok = result.exitCode == 0 && !result.timedOut,
            output = (result.stdout + result.stderr).trim().take(MAX_MOD_OUTPUT),
            timedOut = result.timedOut,
        )
    }

    private suspend fun execute(
        project: Project,
        arguments: List<String>,
        timeoutMs: Int,
    ): GoCommandResult {
        if (timeoutMs <= 0) mcpFail("timeoutMs must be positive, got $timeoutMs")
        // Process execution blocks, so it belongs off the coroutine's default dispatcher.
        return withContext(Dispatchers.IO) {
            runner.run(project, arguments, project.basePath, timeoutMs.toLong())
        }
    }

    private fun GoCommandResult.toCheckResult(): GoCheckResult {
        val parsed = GoDiagnosticsParser.parse(stderr + "\n" + stdout)
        return GoCheckResult(
            command = command,
            ok = exitCode == 0 && !timedOut,
            diagnostics = parsed.diagnostics.take(MAX_DIAGNOSTICS).map {
                GoDiagnosticEntry(it.file, it.line, it.column, it.message)
            },
            notes = parsed.notes.take(MAX_NOTES),
            timedOut = timedOut,
        )
    }

    private fun nullDevice(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "NUL" else "/dev/null"

    private companion object {
        const val MAX_FAILURE_OUTPUT = 4_000
        const val MAX_BUILD_ERRORS = 50
        const val MAX_DIAGNOSTICS = 100
        const val MAX_NOTES = 20
        const val MAX_MOD_OUTPUT = 20_000
        val ALLOWED_MOD_SUBCOMMANDS = setOf("tidy", "download", "verify", "why", "graph")
    }
}
