---
name: go-navigation
description: Use when reading or locating Go code in a project open in GoLand - finding where a symbol is declared, what an API actually looks like, what a package exports, or who calls something. Use instead of grep, ripgrep or find for anything that has a Go meaning. Do not use for editing; see go-refactoring.
---

# Navigating Go code through GoLand

A GoLand session with the Go MCP++ plugin exposes tools that resolve symbols
through the Go type system. Text search cannot do that, and in Go the gap is
wide: `Get` appears in forty packages, a method's receiver is not next to its
type, and vendored copies of a dependency look identical to the real one.

Tool names arrive namespaced by the MCP server, e.g. `mcp__goland__go_symbol`.
Match on the `go_*` suffix.

## Which tool answers which question

| Question | Tool | Arguments |
| --- | --- | --- |
| Where is this declared, and what is its signature? | `go_symbol` | `reference` |
| What does this do — doc comment and signature? | `go_doc` | `reference` |
| Show me its actual source | `go_source_of` | `reference` |
| What does this package export? | `go_package_api` | `packageReference`, `includeUnexported`, `limit` |
| Who uses this, and how? | `go_find_usages` | `reference`, `includeTests`, `limit` |
| Read several files at once | `go_read_files` | `paths` |

## Writing a reference

One string, in any of these shapes:

- `net/http.Client.Do` — import path, type, member
- `net/http.Client` — import path and symbol
- `./internal/store.Store` — relative package
- `store.User.Save` — single-segment package
- `Handler.ServeHTTP` — receiver and method
- `ServeHTTP` — bare name

Quoting, a pasted declaration (`func (c *Circle) Area() float64`), and the
documentation form (`(*Circle).Area`) are all accepted and normalised. Paths
accept a leading `./` or `/`, `file://`, and backslashes.

## Rules that matter

**Never grep for a Go definition.** `go_symbol` resolves; grep matches text and
will happily hand you a same-named symbol from another package, a comment, or a
vendored duplicate.

**Never answer an API question from memory.** `go_doc` reports what the version
installed in *this* module declares. Standard library and dependency symbols
resolve too, so there is no reason to guess at a signature or invent a field.

**Reach for `go_package_api` before exploring a package file by file.** One call
returns the types, functions, constants and variables, with struct fields and
their tags — which is usually the whole question, and it costs one round trip
instead of ten reads.

**`go_find_usages` classifies each usage** as a call, a read or a write, gives
`callCount` and `writeCount`, and marks the ones in test files. "Is this field
ever written outside tests?" is one call, not a search plus manual reading.

When a result reports `truncated`, raise `limit` rather than assuming you saw
everything.
