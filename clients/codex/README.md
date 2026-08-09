# Codex CLI

Codex reads skills from `$CODEX_HOME/skills` (default `~/.codex/skills`), in the
same `SKILL.md` format Claude Code uses — so the four skills are shared rather
than rewritten.

## Install the skills

```bash
mkdir -p ~/.codex/skills
cp -R clients/claude-code/go-mcp-plus/skills/* ~/.codex/skills/
```

Or symlink them, to follow the repository:

```bash
mkdir -p ~/.codex/skills
for skill in clients/claude-code/go-mcp-plus/skills/*/; do
  ln -sfn "$PWD/$skill" ~/.codex/skills/"$(basename "$skill")"
done
```

Restart Codex afterwards so it reloads skill metadata.

This installs `go-navigation`, `go-interfaces`, `go-refactoring` and
`go-testing`.

## Connect to the IDE

Codex keeps MCP servers in `~/.codex/config.toml` under `[mcp_servers.<name>]`.
The JetBrains MCP server listens on a per-instance port, so take the value from
the IDE rather than copying one from documentation: **Settings | Tools | MCP
Server**, then the Codex client entry, or **Copy SSE Config**.

The result looks like this, with `<port>` filled in by the IDE:

```toml
[mcp_servers.goland]
url = "http://localhost:<port>/sse"
```

GoLand also offers to configure this for you when it notices Codex running in a
terminal without a matching setup.

## Project rules

If you would rather state the preference in the repository than install skills,
[`AGENTS.md`](AGENTS.md) is a fragment to append to your project's `AGENTS.md`.
It is a summary, not a replacement: the skills carry the argument shapes and the
traps, which is most of their value.
