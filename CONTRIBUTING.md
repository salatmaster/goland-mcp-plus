# Contributing

## Versions

There is one place a released version is written down: the git tag. `build.gradle.kts`
takes `PLUGIN_VERSION` from the environment and the release workflow derives it from
the tag; `pluginVersion` in `gradle.properties` is the fallback a local build carries.
The Claude Code plugin manifests carry no version at all, so Claude Code falls back to
the commit SHA. Do not add one back.

## Building

```bash
./gradlew test                    # the whole suite, no Go toolchain needed
./gradlew verifyPluginStructure   # Marketplace rules the build does not check
./gradlew buildPlugin             # zip in build/distributions
./gradlew runIde                  # a sandbox IDE with the plugin loaded
```

Tests run against a light in-memory fixture. That keeps them fast and portable, at
one cost worth knowing about: without a Go SDK, builtin types like `int` do not
resolve, so anything derived from type resolution — zero values, inheritor search —
behaves differently there than in a real project. Where that matters, the test says
so.

## Layout

```
common/     references, paths, documents, diffs — no Go API
go/         the only package that touches com.goide.*
toolchain/  running the go binary and parsing its output
metrics/    usage counters and the settings page
toolset/    the @McpTool surface
```

The direction of dependency is one way: `toolset` may use `go`, `go` may not use
`toolset`. A test fails if a toolset imports `com.goide.*` directly.

## Adding a tool

1. Put the Go API access in `go/`, behind an interface, returning plain data.
2. Add a thin `@McpTool` method in a toolset that delegates to an
   `internal suspend fun` taking `Project` explicitly. Tests call the internal one:
   building an `McpCallInfo` would require an internal class of the MCP server plugin.
3. Wrap the `@McpTool` body in `tracked("go_your_tool") { ... }`.
4. Return a `@Serializable` data class with properties. Never a bare `List`.
5. Give every parameter a description, and no default value.
6. Write tests that assert the *file contents* after a mutation, not just the result
   object.

## Conventions that are not obvious

- **Mutating tools use `WriteCommandAction`** so the change lands on the undo stack,
  and return a unified diff rather than "ok".
- **Truncation reports a boolean and a hint, never a count.** The search stops at
  `limit + 1`, so a count would always be 1.
- **Resolve paths through content roots**, never `LocalFileSystem`: a light test
  fixture lives in an in-memory filesystem that `LocalFileSystem` cannot see.
- **`org.junit.Assert.assertThrows` must be fully qualified** — `UsefulTestCase`
  declares its own, returning `void`.
- **Avoid a no-argument lambda returning `Unit` inside a method named `test…`.** It
  compiles to a synthetic method that JUnit 3 then collects as a test and fails. Use
  try/catch instead.
- **Never swallow cancellation.** `runCatchingCancellable` exists for this;
  `ProcessCanceledException` extends `CancellationException`.

## Keep the plugin dynamic

`DynamicPluginTest` asserts that the plugin loads, unloads and reloads without an IDE
restart. The IDE grants that only while every extension point in use is declared
`dynamic` and the plugin declares no components. Nothing warns you at build time if a
new extension breaks it — users simply start getting a restart prompt on every update.
Before adding an extension, check that its extension point is declared `dynamic="true"`
in the platform or in the plugin that owns it.

## Error messages

The caller is a model. An error that says only what is wrong leaves it guessing at
the fix, and its next guess is usually worse. Say what was wrong, what was expected,
and — where it is short — show the current state. `go_change_signature` prints the
signature it is refusing to change for exactly this reason.

## A tool that is sometimes wrong is worse than no tool

Two tools were dropped rather than shipped half-working, and the README says why.
An agent cannot tell a subtly wrong answer from a right one, and neither can the
person reviewing its diff. If a tool cannot be made correct, leave it out and
document the gap.
