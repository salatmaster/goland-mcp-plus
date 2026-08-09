package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase

class GoPackagesTest : GoMcpToolTestCase() {

    private val packages: GoPackages = GoPackagesImpl()

    private fun api(includeUnexported: Boolean = false) =
        runReadAction { packages.api(project, "basic", includeUnexported) }!!

    fun `test lists exported declarations only by default`() {
        loadFixture("basic")
        val result = api()

        val typeNames = result.types.map { it.name }
        assertTrue("User should be listed, was $typeNames", typeNames.contains("User"))

        val functionNames = result.functions.map { it.name }
        assertTrue("NewUser should be listed, was $functionNames", functionNames.contains("NewUser"))
        assertFalse("helper is unexported, was $functionNames", functionNames.contains("helper"))
    }

    fun `test includes unexported declarations on request`() {
        loadFixture("basic")
        val functionNames = api(includeUnexported = true).functions.map { it.name }

        assertTrue("helper should appear, was $functionNames", functionNames.contains("helper"))
    }

    fun `test reports struct fields with their tags`() {
        loadFixture("basic")
        val user = api().types.single { it.name == "User" }

        val id = user.fields.single { it.name == "ID" }
        assertEquals("int", id.type)
        assertTrue("tag should be carried, was '${id.tag}'", id.tag.contains("""json:"id""""))
        assertTrue("tag should carry db too, was '${id.tag}'", id.tag.contains("""db:"user_id""""))

        assertFalse(
            "unexported field should be hidden by default",
            user.fields.any { it.name == "secret" },
        )
    }

    fun `test marks embedded fields`() {
        loadFixture("basic")
        val audit = api().types.single { it.name == "Audit" }

        val embedded = audit.fields.single { it.embedded }
        assertEquals("User", embedded.name)
    }

    fun `test attaches methods to their type`() {
        loadFixture("basic")
        val rect = api().types.single { it.name == "Rect" }

        assertEquals(listOf("Area", "Name"), rect.methods.map { it.name }.sorted())
        assertTrue(
            "method should record its receiver, was '${rect.methods.first().receiver}'",
            rect.methods.first().receiver.contains("Rect"),
        )
    }

    fun `test lists constants variables and docs`() {
        loadFixture("basic")
        val result = api()

        assertTrue(result.constants.map { it.name }.contains("MaxUsers"))
        assertTrue(result.variables.map { it.name }.contains("DefaultName"))

        val user = result.types.single { it.name == "User" }
        assertTrue("doc should be carried, was '${user.doc}'", user.doc.contains("struct tags"))
    }

    fun `test returns null for an unknown package`() {
        loadFixture("basic")
        assertNull(runReadAction { packages.api(project, "nosuchpackage", false) })
    }
}
