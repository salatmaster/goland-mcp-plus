# Security

## Reporting

Report a vulnerability through
[GitHub's private advisory form](https://github.com/salatmaster/goland-mcp-plus/security/advisories/new).
Please do not open a public issue first.

Expect an acknowledgement within a week.

## What this plugin can do

It contributes tools to the MCP server built into your JetBrains IDE. Anything that
can talk to that server can call them, with the same reach the IDE has:

- **Read** any file in an open project, and in its dependencies and SDK.
- **Modify** source files: refactorings, generated code, batched text edits. Every
  change goes through a write command, so it is on the IDE's undo stack.
- **Run the Go toolchain**: `go test`, `go build`, `go vet`, `go mod`, using the
  project's configured SDK, in the project directory.

It does not open network connections of its own, read credentials, or start
processes other than the Go toolchain.

## What it never does

- No telemetry. The usage counters under **Settings | Tools | Go MCP++** are held in
  memory for the session, are never written to disk, and never leave the machine.
- No data is sent anywhere. This plugin has no server component.

## Your exposure is the MCP server's exposure

The plugin adds no transport. Who may reach the IDE's MCP server, and on what port,
is decided by **Settings | Tools | MCP Server** — including the built-in tool filter
and the confirmation prompt for terminal commands. Review those settings before
connecting an agent you do not control, and be aware that enabling the MCP server
grants access to *every* project open in that IDE.

`go_test` and the other toolchain tools execute the project's own code. That is what
running tests means, and it is worth stating: pointing an agent at an untrusted
repository and letting it run tests executes that repository's code on your machine.
