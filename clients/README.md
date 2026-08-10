# Agent clients

The plugin exposes the tools; these make an agent reach for them. That is a
separate problem: a model that does not know a tool exists falls back to `grep`,
and in Go that quietly gives wrong answers.

The skills live once, in
[`../plugins/goland-mcp-plus/skills`](../plugins/goland-mcp-plus/skills). One plugin
directory carries a manifest for each client — `.claude-plugin/plugin.json` and
`.codex-plugin/plugin.json` — over the same files. It has to be one real directory
rather than a link: Codex copies the plugin into its own cache, and a symlink
pointing out of it installs as a plugin with no skills in it, reporting success.

| Skill | Fires on |
| --- | --- |
| `go-mcp` | any work in a Go codebase; routes to the rest |
| `go-navigation` | finding a declaration, an API, callers |
| `go-interfaces` | who implements what, and why something does not |
| `go-refactoring` | signature changes, safe delete, inline, moves |
| `go-testing` | tests, builds, vet, modules |

Skills load on demand — cheap, and also their weakness: a task that does not read
as "Go navigation" can finish without any of them loading. `go-mcp` exists for
that, with a description covering any Go work at all.

For a hard guarantee, each client has a project-rules file, always in context:
paste [`claude-code/CLAUDE.md`](claude-code/CLAUDE.md) or append
[`codex/AGENTS.md`](codex/AGENTS.md). Both are summaries — the skills carry the
argument shapes and the traps.

## Install

- **Claude Code** — [claude-code/](claude-code)
- **Codex** — [codex/](codex)

Both assume the IDE is already connected. The MCP server is the IDE itself, on a
port assigned per instance, so take the configuration from **Settings | Tools | MCP
Server** — **Auto-Configure** for a client it recognises, otherwise **Copy config:
HTTP Stream** — rather than writing a port by hand. SSE is still offered there, but
MCP has deprecated it in favour of streamable HTTP.

Check it by asking the agent to call `go_symbol` on any type in your project; if the
tools are missing, no skill helps.
