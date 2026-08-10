# Codex CLI

Codex reads skills from `$CODEX_HOME/skills` (default `~/.codex/skills`), in the
same `SKILL.md` format Claude Code uses, so the five skills are shared rather than
written twice.

```bash
mkdir -p ~/.codex/skills
cp -R skills/* ~/.codex/skills/
```

Or symlink them, to follow the repository:

```bash
for skill in skills/*/; do
  ln -sfn "$PWD/$skill" ~/.codex/skills/"$(basename "$skill")"
done
```

Restart Codex afterwards so it reloads skill metadata.

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

GoLand also offers to configure this when it notices Codex running in a terminal
without a matching setup. If you would rather paste it yourself, use **Copy config:
HTTP Stream** rather than SSE, which MCP has deprecated.
