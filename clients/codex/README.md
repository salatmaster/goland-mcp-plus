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

MCP servers live in `~/.codex/config.toml` under `[mcp_servers.<name>]`. The
JetBrains server listens on a per-instance port, so take the value from **Settings |
Tools | MCP Server** rather than copying one from documentation:

```toml
[mcp_servers.goland]
url = "http://localhost:<port>/sse"
```

GoLand also offers to configure this when it notices Codex running in a terminal
without a matching setup.
