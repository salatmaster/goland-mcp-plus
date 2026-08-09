package dev.salatmaster.golandmcp.toolchain

import com.goide.sdk.GoSdkService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project

data class GoCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * Runs Go toolchain commands using the SDK configured in the IDE.
 *
 * Taking the executable from [GoSdkService] rather than the ambient PATH means the command
 * sees the same toolchain the developer does. A shell may resolve a different Go version, or
 * none at all, which produces failures that do not reproduce in the IDE.
 */
class GoToolchainRunner {

    fun run(
        project: Project,
        arguments: List<String>,
        workingDirectory: String?,
        timeoutMs: Long,
    ): GoCommandResult {
        val executable = goExecutable(project)
        val commandLine = GeneralCommandLine(listOf(executable) + arguments)
            .withWorkDirectory(workingDirectory ?: project.basePath)
            .withRedirectErrorStream(false)

        val handler = CapturingProcessHandler(commandLine)
        val output = handler.runProcess(timeoutMs.toInt(), true)

        return GoCommandResult(
            command = "go " + arguments.joinToString(" "),
            exitCode = output.exitCode,
            stdout = output.stdout,
            stderr = output.stderr,
            timedOut = output.isTimeout,
        )
    }

    /** Falls back to PATH so the tools still work before an SDK has been configured. */
    private fun goExecutable(project: Project): String =
        GoSdkService.getInstance(project).getSdk(null)?.executable?.path ?: "go"
}
