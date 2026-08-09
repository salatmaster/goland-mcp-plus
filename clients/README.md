# Agent clients

The IDE plugin exposes the tools. These directories make an agent actually reach
for them, which is a separate problem: a model that does not know a tool exists
falls back to `grep`, and in Go that quietly gives wrong answers.

- [`claude-code/`](claude-code) — a Claude Code plugin with four skills
- [`codex/`](codex) — the same skills for the Codex CLI, plus its MCP configuration

Both sets of skills are the same files. Claude Code installs them as a plugin;
Codex reads them from `~/.codex/skills`.

## First: connect the agent to the IDE

The MCP server is the one built into your JetBrains IDE, not a separate process,
and it listens on a port that is assigned per IDE instance. **Do not hand-write
the port.** Let the IDE emit the configuration:

1. Open **Settings | Tools | MCP Server** in GoLand.
2. Enable the server if it is not already running.
3. Use the client entry for Claude Code or Codex to configure it automatically,
   or **Copy SSE Config** / **Copy Stdio Config** and paste into your client.

Verify the connection by asking the agent to call `go_symbol` on any type in your
project. If the tools are missing, the IDE is not connected; no skill can fix
that.
