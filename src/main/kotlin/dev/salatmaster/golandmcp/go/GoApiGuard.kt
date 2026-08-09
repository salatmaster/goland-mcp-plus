package dev.salatmaster.golandmcp.go

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.logger

private class GoApiGuardMarker

private val LOG = logger<GoApiGuardMarker>()

/**
 * Runs a block that touches the closed-source `com.goide.*` API.
 *
 * That API carries no compatibility guarantees, so a GoLand upgrade can remove or
 * reshape a method. Without this guard the resulting [LinkageError] would escape as
 * an unhandled throwable and drop the MCP session; here it becomes an ordinary tool
 * error naming the IDE build, which is what a bug report needs.
 */
fun <T> guardGoApi(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: LinkageError) {
        val build = ApplicationInfo.getInstance().build.asString()
        LOG.warn("Go API call '$what' failed against build $build", e)
        mcpFail(
            "GoLand MCP+ could not use the Go plugin API for '$what' on IDE build $build. " +
                "This usually means the plugin was built against a different GoLand version. " +
                "Details: ${e::class.simpleName}: ${e.message}",
        )
    }
