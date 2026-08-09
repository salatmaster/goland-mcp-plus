# Claude Code plugin

Four skills that route Go questions to the Go MCP++ tools instead of to `grep`.

| Skill | Fires on |
| --- | --- |
| `go-navigation` | finding a declaration, an API, a package's exports, callers |
| `go-interfaces` | who implements this, why does this not satisfy that |
| `go-refactoring` | signature changes, safe delete, inline, moving files |
| `go-testing` | running tests, checking the build, `go vet`, modules |

## Install

From this repository, as a marketplace:

```
/plugin marketplace add salatmaster/goland-mcp-plus
/plugin install go-mcp-plus@go-mcp-plus
```

Or from a local checkout:

```
/plugin marketplace add /path/to/goland-mcp-plus
/plugin install go-mcp-plus@go-mcp-plus
```

Skills are namespaced by the plugin, so they appear as
`go-mcp-plus:go-interfaces` and so on. Claude invokes them on its own when a
task matches the description; you can also invoke one directly.

## What the plugin does not do

It does not configure the MCP connection. The server belongs to the IDE and its
port is per-instance — see [../README.md](../README.md). The skills assume the
tools are already reachable and say so in their triggers.
