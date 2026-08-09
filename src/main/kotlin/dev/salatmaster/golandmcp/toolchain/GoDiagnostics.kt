package dev.salatmaster.golandmcp.toolchain

data class GoDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
)

/**
 * Parses the `file:line[:column]: message` diagnostics emitted by `go build` and `go vet`.
 *
 * Anything that does not match that shape is kept as a note rather than dropped: linker
 * errors and toolchain complaints carry no position but still explain the failure.
 */
object GoDiagnosticsParser {

    private val pattern = Regex("""^(.+?\.go):(\d+)(?::(\d+))?:\s*(.+)$""")

    data class Parsed(
        val diagnostics: List<GoDiagnostic>,
        val notes: List<String>,
    )

    fun parse(output: String): Parsed {
        val diagnostics = mutableListOf<GoDiagnostic>()
        val notes = mutableListOf<String>()

        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val match = pattern.find(line)
            if (match == null) {
                notes += line
                continue
            }

            val (file, lineNumber, column, message) = match.destructured
            diagnostics += GoDiagnostic(
                file = file.removePrefix("./"),
                line = lineNumber.toIntOrNull() ?: 0,
                column = column.toIntOrNull() ?: 0,
                message = message,
            )
        }
        return Parsed(diagnostics, notes)
    }
}
