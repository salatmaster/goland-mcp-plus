# Codex CLI

```bash
codex plugin marketplace add salatmaster/goland-mcp-plus
codex plugin add goland-mcp-plus@goland-mcp-plus
```

Or point the marketplace at a local checkout instead of the GitHub slug — the same
plugin directory serves both clients, so nothing is duplicated between them.

That installs the five skills from
[`../../plugins/goland-mcp-plus/skills`](../../plugins/goland-mcp-plus/skills), in
the same `SKILL.md` format Claude Code reads. Codex picks them up without a restart.

On a Codex too old for `codex plugin`, copy them instead:

```bash
mkdir -p ~/.codex/skills
cp -R plugins/goland-mcp-plus/skills/* ~/.codex/skills/
```

## Then the always-on rule

Skills load on demand, so append [`AGENTS.md`](AGENTS.md) to your project's
`AGENTS.md` — project rules are always in context, which is exactly the mechanism
Codex already has.

## Connect to the IDE

Codex keeps MCP servers in `~/.codex/config.toml` under `[mcp_servers.<name>]`, and
GoLand writes that entry for you: **Settings | Tools | MCP Server**, then
**Auto-Configure**. It uses streamable HTTP and keeps the port in sync, which matters
because the port is assigned per IDE instance — a URL copied from documentation will
be wrong, or worse, will reach a different IDE.

That is also why the plugin bundles no MCP server of its own: there is no port that
would be right for a second machine.

GoLand also offers to configure this when it notices Codex running in a terminal
without a matching setup. If you would rather paste it yourself, use **Copy config:
HTTP Stream** rather than SSE, which MCP has deprecated.
