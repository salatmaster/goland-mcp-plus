<h1 align="center">Go MCP++</h1>

<p align="center">
  <b>Your agent stops grepping Go and starts asking the compiler.</b><br>
  24 Go tools for the MCP server built into GoLand.
</p>

---

Go has no `implements` keyword. Which types satisfy an interface is written down
nowhere — it exists only in the IDE's model. So when an agent reaches for `grep`,
it gets answers that look right and are wrong.

```
You:   Make Circle satisfy Shape.
Agent: [writes Area() and Name() on *Circle]
Go:    cannot use circle (variable of type Circle) as Shape value:
       Circle does not implement Shape
Agent: ...
```

The compiler says *that* it failed, never *why*. One tool call does:

```jsonc
go_interface_check(typeName="Circle", interfaceName="Shape")

{ "satisfied": false,
  "checkedAs": "*Circle",
  "pointerReceiverOnly": ["Area", "Name"],
  "hint": "Circle declares Area, Name on a pointer receiver, so *Circle satisfies
           Shape but Circle does not. Either use *Circle at the call site, or
           change those methods to value receivers." }
```

That gap runs through the language. `grep Close` finds forty unrelated methods and
misses every one promoted through embedding. A signature change means finding every
caller and getting each argument order right. Neither is a text problem.

## Install

**1. The plugin.** Download the zip from
[the latest release](https://github.com/salatmaster/goland-mcp-plus/releases/latest),
then **Settings | Plugins | ⚙ | Install Plugin from Disk**.

*(Not on the JetBrains Marketplace yet. Building from source is a contributor path —
see [CONTRIBUTING](CONTRIBUTING.md).)*

**2. Point your agent at the IDE.** The MCP server *is* the IDE, on a port it
computes per instance — so never write the port by hand. Open **Settings | Tools |
MCP Server**, enable it, then:

| Client | How |
| --- | --- |
| Claude Code, Codex, Cursor and friends | **Auto-Configure** — the IDE writes the config and keeps the port in sync |
| anything else | **Copy config: HTTP Stream**, paste into your client |

Prefer HTTP Stream. SSE is offered too, but it is the transport MCP has since
deprecated in favour of streamable HTTP; take Stdio only for a client that speaks
nothing else.

GoLand also raises a banner when it notices Claude or Codex start in a terminal
without a matching setup.

Check it worked by asking the agent to call `go_symbol` on any type in your project.

**3. Teach the agent the tools exist.** A connected agent still reaches for `grep`.
[`clients/`](clients) ships five skills, shared by both clients:

```
# Claude Code
/plugin marketplace add salatmaster/goland-mcp-plus
/plugin install goland-mcp-plus@goland-mcp-plus

# Codex
cp -R skills/* ~/.codex/skills/
```

For any other agent, the same guidance in one page: paste
[`clients/claude-code/CLAUDE.md`](clients/claude-code/CLAUDE.md) into whatever
always-in-context rules file it has.

## Tools

**Understand** — `go_symbol` · `go_doc` · `go_source_of` · `go_package_api` ·
`go_find_usages` · `go_read_files`

> Resolve a symbol through the type system, read what the *installed* version of a
> dependency declares, list a package's API with struct tags, find callers
> classified as call, read or write.

**Interfaces** — `go_interface_check` · `go_implementations` · `go_interfaces_of` ·
`go_implement_interface` · `go_extract_interface`

> Who implements this, which interfaces a type satisfies, why it does not, and the
> stubs to fix it — including the pointer-receiver trap the compiler will not
> distinguish for you.

**Change** — `go_change_signature` · `go_safe_delete` · `go_inline` ·
`go_move_files` · `go_fix_imports` · `go_replace_lines` · `go_batch_replace_text`

> Rename, add, drop, reorder or retype parameters and rewrite every call site.
> Delete only what nothing references. Move files between packages, updating every
> importer. Every change lands on the IDE's undo stack — one Cmd+Z reverts an agent.

**Generate** — `go_type_from_json` · `go_generate_test`

> JSON into Go structs with golint initialisms (`id` → `ID`). Table-driven test
> scaffolding, creating the `_test.go` if it does not exist.

**Toolchain** — `go_test` · `go_build_check` · `go_vet` · `go_mod`

> Run the toolchain with the SDK the project is configured with, and get each
> failure with its own output instead of a log to re-read.

References are written the way you would say them — `net/http.Client.Do`,
`store.User.Save`, `Handler.ServeHTTP`, or a bare name. A pasted declaration and
the `(*Circle).Area` documentation form work too.

## Requirements

GoLand 2026.2 or later, with the bundled MCP Server and Go plugins enabled.

There is no upper version bound: pinning one would make the plugin uninstallable
the day you update the IDE. Instead, an incompatible Go API surfaces as an ordinary
tool error naming the build, so a bug report says what broke.

## No telemetry

**Settings | Tools | Go MCP++** shows how the agent used the tools this session —
calls, failures, cancellations, average duration. In memory only, never written to
disk, never transmitted. A tool at zero calls usually means the agent was never told
it exists.

## Also worth knowing

`go_extract_function` is deliberately absent: the IDE's handler needs an editor and
an inplace naming template that a tool call cannot answer, and the text-splicing
alternative gets captured variables wrong. A tool that is sometimes wrong is worse
than no tool.

[Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) ·
[Security](SECURITY.md) · [Apache-2.0](LICENSE)
