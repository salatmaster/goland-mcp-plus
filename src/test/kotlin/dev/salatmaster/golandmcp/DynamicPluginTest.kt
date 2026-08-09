package dev.salatmaster.golandmcp

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The plugin installs and updates without restarting the IDE, and this is the test that
 * keeps it that way.
 *
 * Dynamic loading is not a switch to turn on: the IDE grants it only while every extension
 * point the plugin uses is declared `dynamic`, and it declares no components. Adding a
 * single non-dynamic extension silently costs every user a restart on every update, with no
 * warning at build time — which is exactly the kind of regression a test should catch.
 */
class DynamicPluginTest : BasePlatformTestCase() {

    private fun descriptor(): PluginMainDescriptor {
        val id = PluginId.getId(PLUGIN_ID)
        val found = PluginManagerCore.getPlugin(id)
        assertNotNull("$PLUGIN_ID should be loaded in the test sandbox", found)
        return found as PluginMainDescriptor
    }

    fun `test the plugin can be installed without restarting the IDE`() {
        val reason = DynamicPlugins.validateCanLoadWithoutRestart(descriptor())

        assertNull(
            "Installing would now require a restart. Every extension point must be " +
                "declared dynamic. Reason: $reason",
            reason,
        )
    }

    fun `test the plugin can be updated or removed without restarting the IDE`() {
        val reason = DynamicPlugins.validateCanUnloadWithoutRestart(descriptor())

        assertNull(
            "Updating or removing would now require a restart. Reason: $reason",
            reason,
        )
    }

    fun `test the descriptor does not ask for a restart itself`() {
        assertFalse(
            "plugin.xml must not set require-restart",
            descriptor().isRequireRestart,
        )
    }

    private companion object {
        const val PLUGIN_ID = "io.github.salatmaster.goland-mcp-plus"
    }
}
