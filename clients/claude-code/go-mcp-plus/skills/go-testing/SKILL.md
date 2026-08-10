---
name: go-testing
description: Use when running, writing or diagnosing Go tests and builds in a project open in GoLand - running go test, checking that a package still compiles, running go vet, scaffolding a table test, or managing modules. Use instead of running go through a shell, because the results come back structured.
---

# Go tests and toolchain through GoLand

These tools run the toolchain with the SDK the project is actually configured
with, and parse the output into structure. Running `go test` in a shell gives
you text to re-read; these give you the failing test, its output, and the file
and line.

Tool names arrive namespaced by the MCP server; match on the `go_*` suffix.

| Task | Tool | Arguments |
| --- | --- | --- |
| Run tests | `go_test` | `packagePattern`, `runPattern`, `moduleDirectory`, `timeoutMs` |
| Does it still compile? | `go_build_check` | `packagePattern`, `moduleDirectory`, `timeoutMs` |
| Run go vet | `go_vet` | `packagePattern`, `moduleDirectory`, `timeoutMs` |
| Module housekeeping | `go_mod` | `subcommand`, `arguments`, `moduleDirectory`, `timeoutMs` |
| Scaffold a table test | `go_generate_test` | `functionName`, `receiverType`, `path` |

`packagePattern` takes the usual Go form — `./...`, `./internal/store`. Leave
`runPattern` empty to run everything, or pass a regex to narrow to one test.

**`moduleDirectory` is where `go.mod` lives**, relative to the project root, and
empty means the project root. In a repository where the Go service sits under
`backend/` or `services/api/`, leaving it empty fails with "does not contain main
module" whatever package pattern you pass. When that happens the result lists the
modules it did find — pass one of them.

## Rules that matter

**Check the build after every change that touched more than one file.**
`go_build_check` is cheap and reports `file:line: message` per diagnostic. A
refactoring that leaves the package uncompilable is worse than no refactoring,
and you will not notice by reading the diff.

**A failure to run is not an empty run.** When the toolchain itself fails — no
module, no SDK — the result says so in `buildErrors` and the hint, and no count in
it means anything. Do not read `passed: 0, failed: 0` as "there are no tests".

**Read the structured failures rather than the raw log.** Each failing test
comes back with its own output attached, so quote the assertion that failed, not
the whole run. Build errors that occur before any test runs are preserved
separately — if that list is non-empty, the tests did not run at all and the
pass count means nothing.

**Narrow with `runPattern` when iterating.** Re-running a whole suite to watch
one test is slow and buries the signal.

**`go_generate_test` creates the file** when `path` does not exist yet, joining the
package its directory holds and importing `testing`. It scaffolds the table, with
the cases left for you. Fill in
real cases: a generated table with one empty case is not a test, and leaving it
that way is worse than not generating it, because it looks covered.

**`go_vet` catches what compiles and is still wrong** — a printf verb that does
not match its argument, a lock copied by value. Worth a call before declaring
work finished.

## When something fails to run

If a call reports that the Go SDK is missing, the project has no Go SDK
configured in GoLand; that is a setting in the IDE, not something to work around
by shelling out.
