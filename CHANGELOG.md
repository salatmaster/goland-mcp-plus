# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
