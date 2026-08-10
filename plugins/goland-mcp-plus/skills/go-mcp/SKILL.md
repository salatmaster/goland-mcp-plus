---
name: go-mcp
description: Use at the start of any work in a Go codebase - reading it, changing it, debugging it, reviewing it - whenever a JetBrains IDE with the Go MCP++ tools is connected. Establishes that go_* MCP tools exist and which one answers which question, and routes to the detailed skills. Use even for tasks that sound simple, because the failure mode is not noticing the tools are there and falling back to grep.
---

# Go work goes through the IDE, not through grep

A connected GoLand session exposes `go_*` MCP tools that resolve through the Go
type system. The failure this skill exists to prevent is not using them: an agent
that reaches for `grep` in Go gets answers that look right and are wrong.

Tool names arrive namespaced by the MCP server, e.g. `mcp__goland__go_symbol`.
Match on the `go_*` suffix.

**If no `go_*` tool is listed, stop and say so.** It means the IDE is not connected,
and nothing here applies. Tell the user to open **Settings | Tools | MCP Server** in
GoLand, enable the server, and pick their client from the list — the IDE writes the
configuration itself, because the port is assigned per IDE instance and cannot be
guessed. Do not fall back to grep silently; a wrong answer that looks right is the
outcome this whole tool set exists to prevent.

## Where Go punishes text search specifically

- **Interfaces are structural.** Nothing in the source records that a type
  implements one, so "who implements this" has no textual answer at all.
- **Methods on `*T` are not in `T`'s method set.** The compiler says only "does
  not implement"; which half of that pair you have is invisible in a diff.
- **Methods live away from their type**, and promoted methods live in another file
  entirely, so a type's real method set is never in one place.
- **Package names repeat.** `Get`, `Close`, `New` match everywhere; a vendored
  copy of a dependency looks exactly like the real one.

## Start here

| What you are doing | Skill | First tool |
| --- | --- | --- |
| Finding a declaration, an API, callers | `go-navigation` | `go_symbol`, `go_package_api` |
| Anything about interfaces | `go-interfaces` | `go_interface_check` |
| Changing existing code | `go-refactoring` | `go_change_signature`, `go_safe_delete` |
| Tests, builds, vet, modules | `go-testing` | `go_test`, `go_build_check` |

## The three rules that matter most

1. **Never grep for a Go declaration.** `go_symbol` resolves it; grep matches text.
2. **Never answer an API question from memory.** `go_doc` reports what the version
   installed in this module declares.
3. **Run `go_build_check` after any change touching more than one file.** These
   tools rewrite files you did not read.
