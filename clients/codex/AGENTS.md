<!-- Append to your project's AGENTS.md when GoLand's MCP server is connected. -->

## Go tooling

This project is open in GoLand with the Go MCP++ plugin, which exposes `go_*`
MCP tools that resolve through the Go type system. Prefer them over shell tools:

- **Finding code**: `go_symbol`, `go_doc`, `go_source_of`, `go_package_api`,
  `go_find_usages`. Do not grep for a Go declaration — text search cannot
  distinguish same-named symbols across packages, and misses methods promoted
  through embedding.
- **Interfaces**: `go_implementations`, `go_interfaces_of`, `go_interface_check`.
  Go records no `implements` relationship anywhere in the source, so these
  questions have no textual answer at all. On a "does not implement" error, run
  `go_interface_check` and read `checkedAs` and `pointerReceiverOnly` — methods
  on `*T` are not in `T`'s method set.
- **Refactoring**: `go_change_signature`, `go_safe_delete`, `go_inline`,
  `go_move_files`. These rewrite every call site and land on the IDE's undo
  stack. Read the current signature with `go_symbol` before changing it:
  `go_change_signature` takes the complete new signature, and each entry's
  `fromIndex` is what carries existing arguments through a reorder.
- **Verifying**: `go_build_check` after any multi-file change, then `go_test`.
