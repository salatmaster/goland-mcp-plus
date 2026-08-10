# Claude Code

```
/plugin marketplace add salatmaster/goland-mcp-plus
/plugin install goland-mcp-plus@goland-mcp-plus
```

Or point the marketplace at a local checkout instead of the GitHub slug.

That installs the five skills from [`../../skills`](../../skills) — the plugin's
`skills/` is a symlink there, so there is one copy for both clients. They appear
namespaced, `goland-mcp-plus:go-interfaces`, and Claude invokes them on its own when
a task matches.

## Making sure they get used

`go-mcp` carries a deliberately broad description — any work in a Go codebase,
including tasks that sound simple — so it fires when nothing more specific does.

For a hard guarantee, paste [`CLAUDE.md`](CLAUDE.md) into your project's
`CLAUDE.md`: project rules are always in context, skills are not.

The plugin configures nothing else. The MCP connection belongs to the IDE — see
[../README.md](../README.md).
