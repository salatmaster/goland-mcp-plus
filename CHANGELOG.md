# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- `go_safe_delete` no longer deletes anything but the declaration it was asked for
  and its doc comment. The platform's safe-delete cannot show its conflicts dialog
  to a tool call, and with conflicts suppressed a request to delete one unused
  function also removed an unrelated const, an unrelated var and an import that was
  still in use, leaving the package uncompilable.
- A mention in a comment is no longer counted as a usage. Go's convention is that a
  doc comment opens with the declared name, so `go_safe_delete` had been refusing
  every idiomatically documented declaration, and `go_find_usages` listed comment
  lines among the results.
- `go_test` reports a toolchain that failed before running anything, instead of
  "No tests matched" — a passing-looking answer to a run that never happened.
- `go_fix_imports` no longer reports an unchanged file after removing unused
  imports.
- `go_generate_test`, `go_type_from_json` and `go_extract_interface` create the
  target file when it does not exist, joining the package its directory holds. A
  table test almost always means a `_test.go` nobody has created yet.
- `go_symbol` and the tools built on it resolve `pkg.Symbol`, the form written after
  reading an import.
- `go_package_api` reports `struct` and `interface` rather than a dangling
  `struct {`.

### Added

- `moduleDirectory` on `go_test`, `go_build_check`, `go_vet` and `go_mod`, so a Go
  module in a subdirectory is reachable. When it is wrong, the result lists the
  modules the project does have.

- A test that fails if the plugin ever stops loading and unloading dynamically.
  Installing, updating and removing it need no IDE restart, and nothing else warns
  you at build time when a new extension takes that away.

## [0.1.0] - 2026-08-10

### Added

- 24 Go tools over the IDE's MCP server, in five groups: understanding code,
  interfaces, changing code, generating, and toolchain.
- Interface satisfaction analysis — `go_interface_check` reports *why* a type does
  not satisfy an interface, including the pointer-receiver case the compiler does
  not distinguish, with signature mismatches compared by type rather than by text.
- `go_change_signature` — rewrites a function, method or interface method signature
  and every call site, mapping existing arguments through reorders.
- Agent skills for Claude Code and Codex, in `clients/`.
- A read-only usage page at **Settings | Tools | Go MCP++**: calls, failures,
  cancellations and average duration per tool, held in memory only.
- Tolerant argument parsing: quoted references, pasted declarations, the
  `(*T).Method` documentation form, single-segment package paths, `file://` URLs,
  Windows separators, and paths prefixed with the project directory.

### Not included, deliberately

- `go_extract_function` — the IDE's handler needs an editor and an inplace naming
  template, which a tool call cannot answer. See the README.
- A tool filter UI — the IDE ships one, and these tools appear in it.

[unreleased]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/salatmaster/goland-mcp-plus/releases/tag/v0.1.0
