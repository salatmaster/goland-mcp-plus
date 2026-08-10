---
name: go-refactoring
description: Use when changing existing Go code in a project open in GoLand - renaming or changing a function signature, deleting a declaration, inlining a function, moving files between packages, or fixing imports. Use instead of hand-editing every call site. Do not use for writing new code from scratch.
---

# Refactoring Go through GoLand

These tools drive the IDE's own refactorings, so they rewrite every call site,
update imports across packages, and land on the undo stack — a developer reverts
anything you did with one Cmd+Z. Hand-editing does none of that, and the failure
mode is a call site you never found.

Tool names arrive namespaced by the MCP server; match on the `go_*` suffix.

| Task | Tool | Arguments |
| --- | --- | --- |
| Change a signature, rewrite all calls | `go_change_signature` | `reference`, `newName`, `parameters`, `results`, `updateImplementations` |
| Delete, but only if nothing uses it | `go_safe_delete` | `reference`, `testUsagesBlock` |
| Replace calls with the body | `go_inline` | `reference`, `removeDeclaration` |
| Move files to another package | `go_move_files` | `paths`, `targetDirectory` |
| Add or tidy imports | `go_fix_imports` | `path`, `importsToAdd`, `optimize` |
| Mechanical text edits | `go_replace_lines`, `go_batch_replace_text` | — |

## go_change_signature

**Read the current signature first** with `go_symbol` or `go_source_of`. You
cannot write this call correctly without it.

The request is the **complete new signature, not a patch**. Every entry carries
`fromIndex`, the position it holds in the *current* signature, and that mapping
is what moves an argument along with a reordered parameter instead of dropping
it. Use `-1` for an entry that is new.

Turning `func Pair(a int, b string) string` into `func Pair(b string, a int) string`:

```json
{
  "reference": "Pair",
  "newName": "",
  "parameters": [
    {"fromIndex": 1, "name": "b", "type": "string", "variadic": false, "defaultValue": ""},
    {"fromIndex": 0, "name": "a", "type": "int",    "variadic": false, "defaultValue": ""}
  ],
  "results": [
    {"fromIndex": 0, "name": "", "type": "string", "variadic": false, "defaultValue": ""}
  ],
  "updateImplementations": false
}
```

Rules the tool enforces, and will refuse the call over:

- A **new** parameter needs a `defaultValue` — the expression to write at every
  existing call site (`nil`, `0`, `context.Background()`). Without a usable value
  the calls would be left short an argument.
- Write the plain element type and set `variadic`; do not spell `...`.
- Parameters must be all named or all unnamed. So must results.
- `newName` empty keeps the current name.
- `updateImplementations` matters when the target is an **interface method**: it
  rewrites the implementing types too.

Types named in a new parameter are not imported for you. Run `go_build_check`
afterwards.

## The rest

**`go_safe_delete` refuses when references remain** and lists them, so "is this
dead code?" is answered by trying to delete it. `testUsagesBlock` decides whether
a use in a `_test.go` file counts as a reason to keep it. A mention in a comment is
not a reference; the deletion takes the declaration and its doc comment, and
nothing else in the file.

**`go_move_files` updates the package clause and every importer.** Editing the
package line by hand misses import sites in other packages every time.

**Prefer the semantic tools over `go_batch_replace_text`.** Text replacement in
Go hits comments, strings and same-named symbols in other packages. Use it for
genuinely textual edits, not for renaming things.

## After any refactoring

Run `go_build_check`, and `go_test` if tests cover the area. A refactoring that
compiles is the minimum bar, and these tools change files you did not read.
