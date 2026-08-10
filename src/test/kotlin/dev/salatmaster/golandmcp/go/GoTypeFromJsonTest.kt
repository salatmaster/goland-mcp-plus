package dev.salatmaster.golandmcp.go

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTypeFromJsonTest {

    @Test
    fun `maps scalars to Go types with json tags`() {
        val go = GoTypeFromJson.convert(
            """{"id": 7, "name": "ada", "score": 1.5, "active": true}""",
            "User",
        )

        assertTrue(go, go.contains("""ID int64 `json:"id"`"""))
        assertTrue(go, go.contains("""Name string `json:"name"`"""))
        assertTrue(go, go.contains("""Score float64 `json:"score"`"""))
        assertTrue(go, go.contains("""Active bool `json:"active"`"""))
    }

    @Test
    fun `converts snake case keys to exported names while keeping the tag`() {
        val go = GoTypeFromJson.convert("""{"created_at": "now"}""", "Record")

        assertTrue(go, go.contains("""CreatedAt string `json:"created_at"`"""))
    }

    @Test
    fun `emits nested structs as their own declarations`() {
        val go = GoTypeFromJson.convert("""{"owner": {"name": "ada"}}""", "Repo")

        assertTrue(go, go.contains("type Owner struct"))
        assertTrue(go, go.contains("""Owner Owner `json:"owner"`"""))
    }

    @Test
    fun `maps arrays to slices using the element type`() {
        val go = GoTypeFromJson.convert("""{"tags": ["a"], "items": [{"id": 1}]}""", "Post")

        assertTrue(go, go.contains("""Tags []string `json:"tags"`"""))
        assertTrue(go, go.contains("type Item struct"))
        assertTrue(go, go.contains("""Items []Item `json:"items"`"""))
    }

    @Test
    fun `falls back to any for null and empty arrays`() {
        val go = GoTypeFromJson.convert("""{"maybe": null, "empty": []}""", "Thing")

        assertTrue(go, go.contains("""Maybe any `json:"maybe"`"""))
        assertTrue(go, go.contains("""Empty []any `json:"empty"`"""))
    }

    @Test
    fun `accepts an array sample by using its first element`() {
        val go = GoTypeFromJson.convert("""[{"id": 1}]""", "Row")

        assertTrue(go, go.contains("type Row struct"))
        assertTrue(go, go.contains("""ID int64 `json:"id"`"""))
    }

    @Test
    fun `rejects invalid json with a usable message`() {
        val error = assertThrows(GoTypeFromJson.ConversionException::class.java) {
            GoTypeFromJson.convert("{not json", "X")
        }
        assertTrue(error.message!!, error.message!!.contains("not valid JSON"))
    }

    @Test
    fun `rejects a scalar sample`() {
        assertThrows(GoTypeFromJson.ConversionException::class.java) {
            GoTypeFromJson.convert("42", "X")
        }
    }

    /** golint keeps the plural `s` lowercase: PhotoURLs, not PhotoUrls. */
    @Test
    fun `a plural initialism keeps its lowercase s`() {
        val code = GoTypeFromJson.convert("""{"photo_urls": ["a"], "api_ids": [1]}""", "Row")

        assertTrue("expected PhotoURLs, was:\n$code", code.contains("PhotoURLs []string"))
        assertTrue("expected APIIDs, was:\n$code", code.contains("APIIDs "))
    }
}
