package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadActionBlocking
import dev.salatmaster.golandmcp.GoMcpToolTestCase
import dev.salatmaster.golandmcp.common.parseSymbolRef

/**
 * An interface declared at the use site has to qualify a parameter type its implementing
 * package writes bare. Comparing signature text called that a mismatch, so the real
 * implementation was dropped and only a mock — which qualifies the type the same way the
 * interface does — came back, with `truncated: false` claiming the list was complete.
 */
class GoCrossPackageInterfaceTest : GoMcpToolTestCase() {

    private val facts: GoInterfaceFacts = GoInterfaceFactsImpl()

    fun `test a qualified parameter type still satisfies the interface`() {
        loadFixture("crosspkg")
        val result = (runReadActionBlocking { facts.check(project, parseSymbolRef("Client"), parseSymbolRef("Service")) } as GoCheckOutcome.Checked).satisfaction

        assertTrue(
            "billing.Spec and Spec are the same type; mismatches were ${result.mismatched.map { it.name }}",
            result.mismatched.isEmpty(),
        )
        assertTrue("no method is missing, got ${result.missing}", result.missing.isEmpty())
        assertEquals("*Client", result.checkedAs)
    }

    fun `test the real implementation is listed, not only the mock`() {
        loadFixture("crosspkg")
        val found = runReadActionBlocking { facts.implementors(project, parseSymbolRef("Service"), 30) }!!
            .items.map { it.typeName }

        assertTrue("expected the real client among $found", found.contains("Client"))
        assertTrue("expected the mock among $found", found.contains("Mock"))
    }

    /**
     * A type that embeds the interface implements it while declaring nothing, so it is in no
     * method index. Only following embedding finds it.
     */
    fun `test a type embedding the interface is listed`() {
        loadFixture("crosspkg")
        val found = runReadActionBlocking { facts.implementors(project, parseSymbolRef("Service"), 30) }!!
            .items.map { it.typeName }

        assertTrue("Recorder embeds Service and implements it, got $found", found.contains("Recorder"))
    }

    fun `test the type reports the interface it satisfies`() {
        loadFixture("crosspkg")
        val found = runReadActionBlocking { facts.interfacesOf(project, parseSymbolRef("Client"), 30) }!!
            .items.map { it.interfaceName }

        assertTrue("Client satisfies Service, got $found", found.contains("Service"))
    }

    fun `test the reference it returns identifies the type`() {
        loadFixture("crosspkg")
        val client = runReadActionBlocking { facts.implementors(project, parseSymbolRef("Service"), 30) }!!
            .items.single { it.typeName == "Client" }

        assertTrue(
            "the reference must name the package, was '${client.reference}'",
            client.reference.endsWith("billing.Client"),
        )
    }

    // Wrapper, which embeds *billing.Client from another package, is deliberately not
    // asserted. The candidate search does reach it — GoTypeSpecInheritanceIndex is keyed by
    // the bare embedded name — but deciding satisfaction needs the embedded type resolved,
    // and a light fixture has no module, so the test would fail for the environment rather
    // than the code. Verified by hand instead. Promotion from the same package is covered in
    // GoInterfaceFactsTest, and promotion from an embedded interface by the test above.
}
