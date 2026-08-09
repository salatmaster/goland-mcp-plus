package dev.salatmaster.golandmcp

import com.intellij.mcpserver.annotations.McpTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.salatmaster.golandmcp.toolset.InterfaceToolset
import dev.salatmaster.golandmcp.toolset.PackageToolset
import dev.salatmaster.golandmcp.toolset.SymbolToolset
import kotlinx.serialization.Serializable
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * Guards the failure modes that only surface at runtime: a toolset registered in plugin.xml
 * under a name that does not resolve, and a tool whose return type the MCP schema generator
 * rejects (a bare List fails with "Properties are expected in return type").
 */
class RegistrationTest : BasePlatformTestCase() {

    private val toolsets =
        listOf(SymbolToolset::class, InterfaceToolset::class, PackageToolset::class)

    fun `test every toolset named in plugin xml is instantiable`() {
        val xml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
        val declared = Regex("""mcpServer\.mcpToolset implementation="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toList()

        assertEquals(
            "plugin.xml should register exactly the toolsets this test knows about",
            toolsets.size,
            declared.size,
        )
        for (fqn in declared) {
            val cls = Class.forName(fqn)
            assertNotNull("$fqn should be instantiable", cls.getDeclaredConstructor().newInstance())
        }
    }

    fun `test every tool returns a serializable class with properties`() {
        for (toolset in toolsets) {
            val tools = toolset.memberFunctions.filter { it.findAnnotation<McpTool>() != null }
            assertTrue("${toolset.simpleName} should declare at least one @McpTool", tools.isNotEmpty())

            for (tool in tools) {
                val returned = tool.returnType.jvmErasure
                assertNotNull(
                    "${toolset.simpleName}.${tool.name} must return a @Serializable class, " +
                        "not ${returned.simpleName} — a bare List fails at runtime",
                    returned.findAnnotation<Serializable>(),
                )
                assertTrue(
                    "${toolset.simpleName}.${tool.name} return type must declare properties",
                    returned.memberProperties.isNotEmpty(),
                )
            }
        }
    }

    fun `test required parameters have no defaults`() {
        for (toolset in toolsets) {
            for (tool in toolset.memberFunctions.filter { it.findAnnotation<McpTool>() != null }) {
                // drop(1) skips the receiver parameter.
                for (param in tool.parameters.drop(1)) {
                    assertFalse(
                        "${toolset.simpleName}.${tool.name} parameter '${param.name}' has a " +
                            "default; a name mismatch would then silently use it instead of failing",
                        param.isOptional,
                    )
                }
            }
        }
    }
}
