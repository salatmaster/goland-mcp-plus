package dev.salatmaster.golandmcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * One plugin directory serves Claude Code and Codex. They read different manifests over
 * the same `skills/`, so the two drift apart silently unless something checks.
 *
 * The symlink test is not hypothetical. The skills used to live at the repository root
 * with a symlink into the plugin directory, and Codex — which copies the plugin subtree
 * into its cache rather than reading it in place — installed a plugin containing no
 * skills at all, while reporting success.
 */
class PluginManifestsTest {

    @Test
    fun `the manifests agree on identity`() {
        val claude = json("$PLUGIN/.claude-plugin/plugin.json")
        val codex = json("$PLUGIN/.codex-plugin/plugin.json")

        assertEquals("goland-mcp-plus", claude.string("name"))
        assertEquals(claude.string("name"), codex.string("name"))
        assertEquals(claude.string("license"), codex.string("license"))
        assertEquals(claude.string("repository"), codex.string("repository"))
    }

    /**
     * A released version is written in exactly one place, the tag. Repeating it here would
     * be a second place to forget, and neither client needs it to install.
     */
    @Test
    fun `neither manifest pins a version`() {
        assertNull(json("$PLUGIN/.claude-plugin/plugin.json").string("version"))
        assertNull(json("$PLUGIN/.codex-plugin/plugin.json").string("version"))
    }

    @Test
    fun `the Codex manifest points at the shared skills`() {
        assertEquals("./skills/", json("$PLUGIN/.codex-plugin/plugin.json").string("skills"))
    }

    /**
     * The MCP server is deliberately not bundled: it is the IDE, on a port computed per
     * instance, so a `.mcp.json` here would be wrong for everyone but its author.
     */
    @Test
    fun `no bundled MCP server`() {
        assertTrue(!File("$PLUGIN/.mcp.json").exists())
        assertNull(json("$PLUGIN/.codex-plugin/plugin.json").string("mcpServers"))
    }

    @Test
    fun `both marketplaces list the plugin at a path that exists`() {
        val claude = json(".claude-plugin/marketplace.json")
            .jsonArray("plugins").first().jsonObject
        assertEquals("goland-mcp-plus", claude.string("name"))
        assertEquals("./$PLUGIN", claude.string("source"))

        val codex = json(".agents/plugins/marketplace.json")
            .jsonArray("plugins").first().jsonObject
        assertEquals("goland-mcp-plus", codex.string("name"))
        assertEquals("./$PLUGIN", codex["source"]?.jsonObject?.string("path"))

        assertTrue("$PLUGIN must exist", File(PLUGIN).isDirectory)
    }

    @Test
    fun `every skill directory has a SKILL file`() {
        val skills = File("$PLUGIN/skills").listFiles()?.filter { it.isDirectory }.orEmpty()

        assertTrue("expected the skills to be there, found none", skills.isNotEmpty())
        for (skill in skills) {
            assertTrue(
                "${skill.name} has no SKILL.md",
                File(skill, "SKILL.md").isFile,
            )
        }
    }

    /**
     * Codex copies the plugin subtree, and a symlink pointing out of it does not survive
     * the copy — the install then succeeds with nothing in it.
     */
    @Test
    fun `nothing in the plugin is a symlink`() {
        val links = File(PLUGIN).walkTopDown()
            .filter { Files.isSymbolicLink(it.toPath()) }
            .map { it.path }
            .toList()

        assertEquals("a symlink here installs as an empty plugin", emptyList<String>(), links)
    }

    private fun json(path: String): JsonObject {
        val file = File(path)
        assertTrue("no such file: $path", file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.jsonArray(key: String) =
        requireNotNull(this[key]) { "no '$key' in the manifest" }.jsonArray

    private companion object {
        const val PLUGIN = "plugins/goland-mcp-plus"
    }
}
