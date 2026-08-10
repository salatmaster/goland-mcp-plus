# Security

Report a vulnerability through
[GitHub's private advisory form](https://github.com/salatmaster/goland-mcp-plus/security/advisories/new)
rather than a public issue. Expect an acknowledgement within a week.

## What the tools can reach

They are contributed to the MCP server built into your IDE, so anything that can
talk to that server can call them, with the reach the IDE has:

- **Read** any file in an open project, its dependencies and its SDK.
- **Modify** source: refactorings, generated code, batched edits. Every change goes
  through a write command, so it is on the undo stack.
- **Run the Go toolchain** — `test`, `build`, `vet`, `mod` — with the project's SDK,
  in the project directory.

It opens no network connections of its own, reads no credentials, and starts no
process other than the Go toolchain.

## No data leaves the machine

The usage counters under **Settings | Tools | Go MCP++** live in memory for the
session, are never written to disk, and are never transmitted. The plugin has no
server component and no telemetry.

## Your exposure is the MCP server's exposure

This plugin adds no transport. Who may reach the IDE's MCP server is decided by
**Settings | Tools | MCP Server**, including its tool filter and the confirmation
prompt for terminal commands. Review those before connecting an agent you do not
control: enabling the server grants access to *every* project open in that IDE.

And `go_test` runs the project's own code. That is what running tests means — but
it is worth stating that pointing an agent at an untrusted repository and letting it
run tests executes that repository's code on your machine.
