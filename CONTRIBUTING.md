# Contributing

```bash
./gradlew test                    # the whole suite, no Go toolchain needed
./gradlew verifyPluginStructure   # Marketplace rules the build does not check
./gradlew buildPlugin             # zip in build/distributions
./gradlew runIde                  # a sandbox IDE with the plugin loaded
```

Tests run against a light in-memory fixture, which has one consequence worth
knowing: without a Go SDK, builtins like `int` do not resolve, so anything derived
from type resolution — zero values, inheritor search — behaves differently there
than in a real project. Where that matters, the test says so.

**A green suite is not a verified plugin.** Six defects shipped past it, including
one that deleted an unrelated const, var and import. Run the tools against a real
project before believing them.

## Layout

```
common/     references, paths, documents, diffs — no Go API
go/         the only package that touches com.goide.*
toolchain/  running the go binary and parsing its output
metrics/    usage counters and the settings page
toolset/    the @McpTool surface
```

Dependencies go one way: `toolset` may use `go`, never the reverse. A test fails if
a toolset imports `com.goide.*` directly, so a GoLand upgrade breaks one layer
instead of every tool.

## Adding a tool

1. Put the Go API access in `go/`, behind an interface, returning plain data.
2. Add a thin `@McpTool` method delegating to an `internal suspend fun` that takes
   `Project` explicitly — tests call that one, because building an `McpCallInfo`
   would need an internal class of the MCP server plugin.
3. Wrap the `@McpTool` body in `tracked("go_your_tool") { ... }`.
4. Return a `@Serializable` data class with properties. A bare `List` compiles and
   fails at runtime in the MCP schema generator.
5. Describe every parameter, and give none a default: with one, a mis-spelled
   argument name silently uses it instead of failing.
6. Assert the resulting **file contents**, not just the result object.

## Rules the suite enforces

- Toolsets never import `com.goide.*`.
- Tools return `@Serializable` classes with properties, and take no defaults.
- Every tool records its usage.
- The plugin stays dynamically loadable. The IDE grants that only while every
  extension point in use is `dynamic` and no components are declared, and nothing
  warns you at build time when a new extension takes it away.

## Conventions that are not obvious

- Mutating tools use `WriteCommandAction` so the change lands on the undo stack,
  and return a unified diff rather than "ok".
- Truncation reports a boolean and a hint, never a count: the search stops at
  `limit + 1`, so a count would always be 1.
- Resolve paths through content roots, never `LocalFileSystem` — a light fixture
  lives in an in-memory filesystem it cannot see.
- `org.junit.Assert.assertThrows` must be fully qualified; `UsefulTestCase` declares
  its own returning `void`.
- Avoid a no-argument lambda returning `Unit` inside a method named `test…`: it
  compiles to a synthetic method JUnit 3 collects as a test, and fails. Use
  try/catch.
- Never swallow cancellation — `runCatchingCancellable` exists for it, and
  `ProcessCanceledException` extends `CancellationException`.

## Things deliberately not built

**Platform refactoring processors, where their conflicts are invisible.**
`SafeDeleteProcessor` cannot show its conflicts dialog to a tool call, so conflicts
are suppressed — and suppressed conflicts once removed an unrelated const, an
unrelated var and an in-use import. Deletion is ours: exactly the declaration and
its doc comment.

**`go_extract_function`.** The handler needs an editor and an inplace naming
template that nothing can answer from a tool call.

**A tool filter UI.** The IDE ships one at Settings | Tools | MCP Server | MCP Tool
Filter, and these tools appear in it.

**An executable in the agent plugin.** A `SessionStart` hook could inject the rule
automatically, but `hooks.json` cannot select a handler per platform, so a `sh`
script breaks on Windows without Git Bash — to do what the `go-mcp` skill's own
description already does.

## Error messages are for a model

An error that says only what is wrong leaves the caller guessing, and its next
guess is usually worse. Say what was wrong, what was expected, and show the current
state where it is short. `go_change_signature` prints the signature it is refusing
to change for exactly that reason.
