# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.3] - 2026-08-11

### Fixed

- **The Marketplace shows what changed in a version.** `<change-notes>` was never set, so
  *What's new* was blank for every release from 0.1.0 to 0.2.2 — on the plugin page, and in
  the Plugins dialog when an update is offered. It now carries the changelog entry for the
  version being released. Only future versions can gain it: the Marketplace does not allow
  the notes of an update to be edited once it has been submitted.

### Changed

- **Release notes on GitHub quote the changelog** instead of listing merged pull request
  titles, which recorded what landed rather than what changed for anyone installing the
  plugin. The notes of the seven releases already published were rewritten the same way.

## [0.2.2] - 2026-08-11

### Changed

- **Releases are signed.** Every version from here carries a signature the IDE and the
  Marketplace can verify, and the release refuses to publish when the signing key is
  missing — an update to a signed plugin must itself be signed, and learning that at
  upload time means learning it after the tag exists. Nothing about the tools changes.

## [0.2.1] - 2026-08-11

### Fixed

- **`go_quick_fixes` called an API scheduled for removal.** The five-argument
  `InspectionEngine.inspectEx` is deprecated, and the tool would have stopped working on
  the IDE that drops it; it now uses the supported overload. Nothing changes for a
  caller — the plugin simply survives that release.

## [0.2.0] - 2026-08-10

### Added

- **`go_quick_fixes` and `go_apply_quick_fix`.** The IDE knows both what is wrong and
  how to repair it, and nothing was passing the second half on: the MCP server built
  into the IDE reports problems with no fix attached, and gopls' diagnostics are text.
  These list the fixes the IDE offers for a Go file — what Alt+Enter would show — and
  apply one by name, on the undo stack, returning the diff.

  A fix that needs an editor or asks something interactively is reported rather than
  run; a name matching two problems on one line is refused with both; and a fix that
  runs and changes nothing comes back as `applied: false` with the reason instead of as
  success. Severity is the one the developer's profile gives it, so the agent sees what
  the editor shows rather than a flood of hints called warnings.

  This is the inspection layer, which is where fixes live. Build and type errors still
  come from the IDE's own `get_file_problems`, which reports them but knows no fixes.

## [0.1.3] - 2026-08-10

No change to the tools themselves: not a line of the plugin's behaviour differs from
0.1.2. What was broken was getting the agent to use them.

### Fixed

- **Installing the skills in Codex installed nothing.** `codex plugin add` reported
  success and delivered an empty plugin. The skills were a symlink out of the plugin
  directory, and Codex copies that directory into its own cache rather than reading it
  in place, so the link did not survive. One directory now holds real files for both
  clients, and a test refuses a symlink under `plugins/`.

### Changed

- **Codex installs the skills the way Claude Code does**, with
  `codex plugin marketplace add` and `codex plugin add`, rather than by copying files
  into `~/.codex/skills` by hand. The instructions predated Codex having plugins at all.
- The plugin description leads with what the plugin is for, and states what it needs to
  work: the bundled MCP Server and Go plugins, and an agent connected to the IDE.
- The five skills live in `plugins/goland-mcp-plus`. Anything pointing at the old
  `clients/claude-code/goland-mcp-plus` path needs updating; installing through either
  client's marketplace does not.

## [0.1.2] - 2026-08-10

### Fixed

The interface tools answered confidently and wrongly on code that leans on two
ordinary Go features: a type declared in another package, and embedding.

- **A cross-package interface was reported as unimplemented.** An interface
  declared at the use site has to write `billing.Spec` where the implementing
  package writes `Spec`, and signatures were compared as text, so
  `go_interface_check` said "does not implement" about code that compiles and
  `go_implementations` dropped the real client while keeping the mock. Signatures
  are now compared by type, falling back to text with package qualifiers stripped.
- **`go_implementations` never listed a type that implements by embedding.** The
  search asked which types *declare* a method of the interface's name, so a
  wrapper that embeds a client, or a mock that embeds the interface, appeared
  nowhere — and the result still said the list was complete. It now probes every
  interface method and follows embedding, and says so when a scan stops early
  instead of reporting a partial list as exhaustive.
- **`go_interfaces_of` answered "satisfies nothing"** for the same types, for the
  same reason: it looked only at the methods a type declares.
- **The pointer-receiver answer ignored how a field is embedded.** `struct { Client }`
  and `struct { *Client }` have different method sets when `Client`'s methods take
  a pointer receiver; both were reported as satisfying by value. The advice that
  goes with it no longer says a type "declares" a method it gets by promotion, and
  points at the embedded field instead of at a method in another package.
- **Two types of the same name in different packages collided,** and the second
  was silently dropped from every list.
- **`go_find_usages` now says that calls dispatched through an interface are not
  counted,** and names the interfaces the receiver satisfies.
- **Package-level `var` and `const` did not resolve** in any symbol tool: they are
  in neither the type nor the function index.
- **Plural initialisms:** `photo_urls` now generates `PhotoURLs`.

## [0.1.1] - 2026-08-10

### Fixed

Six defects a live check found, every one of which the test suite was green on.

- **`go_safe_delete` deleted more than it was asked to.** The platform's
  safe-delete cannot show its conflicts dialog to a tool call, and with conflicts
  suppressed a request to remove one unused function also removed an unrelated
  const, an unrelated var and an import still in use. Deletion is now ours: the
  declaration and its doc comment, nothing else.
- **A mention in a comment counted as a usage.** Go's convention opens a doc
  comment with the declared name, so `go_safe_delete` refused every idiomatically
  documented declaration, and `go_find_usages` listed comment lines.
- **`go_test` said "No tests matched" when the toolchain had failed** before
  running anything: package-level events, where build failures arrive, were dropped
  for having no `Test` field.
- **The generation tools refused to write into a file that did not exist** — the
  normal case, since a table test means a `_test.go` nobody has created. They now
  create it, taking the package from a sibling and importing `testing`.
- **`pkg.Symbol` did not resolve.** Two dotted segments are ambiguous with
  `Type.Member`, so it is tried second; package matching now also uses the package
  clause, which is present when a module has not resolved.
- **`go_package_api` reported `struct {`** as a type's underlying shape.

### Added

- 24 Go tools over the IDE's MCP server: understanding code, interfaces, changing
  code, generating, toolchain.
- `go_interface_check`, which reports *why* a type does not satisfy an interface —
  including the pointer-receiver case — with signatures compared by type, not text.
- `go_change_signature`, rewriting a signature and every call site, carrying
  existing arguments through reorders.
- Five agent skills shared by Claude Code and Codex, in `skills/`.
- A read-only usage page at **Settings | Tools | Go MCP++**, in memory only.
- Tolerant arguments: quoted references, pasted declarations, `(*T).Method`,
  single-segment package paths, `file://`, Windows separators.
- `moduleDirectory` on the toolchain tools, so a module in a subdirectory is
  reachable; a wrong one lists the modules that exist.
- A test that fails if the plugin stops being dynamically loadable.

### Changed

- The agent plugin is `goland-mcp-plus`, not `go-mcp-plus` — installing it now reads
  `/plugin install goland-mcp-plus@goland-mcp-plus`. Anyone on the old name should
  reinstall.
- The skills live once in `skills/`, shared by Claude Code and Codex rather than
  filed under the Claude Code plugin.

### Not included, deliberately

`go_extract_function` needs an editor and an inplace naming template a tool call
cannot answer. A tool filter UI would duplicate the one the IDE ships. See
[CONTRIBUTING](CONTRIBUTING.md).

## [0.1.0] - 2026-08-10

First release.

[unreleased]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.2.3...HEAD
[0.2.3]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.1.3...v0.2.0
[0.1.3]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/salatmaster/goland-mcp-plus/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/salatmaster/goland-mcp-plus/releases/tag/v0.1.0
