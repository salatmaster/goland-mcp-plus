# Go MCP++

**Go semantic tools for the MCP server built into GoLand.** 24 tools that answer
questions about Go code the way the IDE answers them — through the type system,
not through text search.

> Listed as **Go MCP++** on the JetBrains Marketplace: plugin names may not contain
> the word "GoLand". The repository keeps its original name.

GoLand already exposes file reading, text search and patching over MCP. What it does
not expose is the Go type system. So an agent cannot ask who implements an interface,
or why a type fails to satisfy one — and Go has no `implements` keyword, so those
answers exist nowhere in the source. They exist only inside the IDE.

---

## The problem it solves

```
You:   Make Circle satisfy the Shape interface.
Agent: [writes Area() and Name() on *Circle]
Go:    cannot use circle (variable of type Circle) as Shape value:
       Circle does not implement Shape
Agent: ...
```

The compiler says *that* it failed, never *why*. With this plugin:

```
Agent: go_interface_check(typeName="Circle", interfaceName="Shape")
       → satisfied: false
         checkedAs: "*Circle"
         pointerReceiverOnly: ["Area", "Name"]
         hint: "Circle declares Area, Name on a pointer receiver, so *Circle
                satisfies Shape but Circle does not. Either use *Circle at the
                call site, or change those methods to value receivers."
```

One call, an actionable answer, no guessing.

The same gap runs through the whole language. `grep Close` finds forty unrelated
methods and misses every one promoted through embedding. A signature change means
finding every caller and getting each argument order right. Neither is a text
problem, and treating them as one is how agents quietly break Go code.

---

## Tools

### Understanding code

| Tool | Answers |
| --- | --- |
| `go_symbol` | What is this — signature, doc, declaration site, exported or not |
| `go_doc` | What does it do, as the installed version declares it |
| `go_source_of` | The declaration's actual source, including from dependencies |
| `go_package_api` | What a package exports, with struct fields and tags |
| `go_find_usages` | Who uses it, classified as call, read or write |
| `go_read_files` | Several files in one round trip |

### Interfaces

| Tool | Answers |
| --- | --- |
| `go_implementations` | Which types implement this interface, and do they need a pointer |
| `go_interfaces_of` | Which interfaces this type satisfies |
| `go_interface_check` | Does it satisfy that interface, and if not, exactly why |
| `go_implement_interface` | Write the methods it is missing |
| `go_extract_interface` | Turn a concrete type's methods into an interface |

### Changing code

| Tool | Does |
| --- | --- |
| `go_change_signature` | Rename, add, drop, reorder or retype parameters and results — rewriting every call site |
| `go_safe_delete` | Delete only if nothing references it; otherwise list what does |
| `go_inline` | Replace calls with the body, substituting arguments |
| `go_move_files` | Move files between packages, updating the package clause and every importer |
| `go_fix_imports` | Add imports, drop unused ones |
| `go_replace_lines`, `go_batch_replace_text` | Mechanical edits, batched |

### Generating

| Tool | Does |
| --- | --- |
| `go_type_from_json` | JSON sample into Go structs, with golint initialisms (`id` becomes `ID`) |
| `go_generate_test` | Scaffold a table-driven test |

### Toolchain

| Tool | Does |
| --- | --- |
| `go_test` | Run tests, returning each failure with its own output attached |
| `go_build_check` | Does it still compile, as `file:line: message` |
| `go_vet` | What compiles and is still wrong |
| `go_mod` | tidy, download, verify, why, graph |

Every mutating tool runs inside a write command, so a developer reverts anything an
agent did with one Cmd+Z.

### Writing a reference

Tools take references the way you would write them, not file coordinates an agent
would have to hunt for first:

```
net/http.Client.Do      import path, type, member
./internal/store.Store  relative package
store.User.Save         single-segment package
Handler.ServeHTTP       receiver and method
ServeHTTP               bare name
```

Quoting, a pasted declaration (`func (c *Circle) Area() float64`) and the
documentation form (`(*Circle).Area`) are normalised rather than rejected — those
are the shapes models actually send.

---

## Install

### The plugin

From the JetBrains Marketplace: **Settings | Plugins | Marketplace**, search for
**Go MCP++**.

Or build it:

```bash
./gradlew buildPlugin
```

and install `build/distributions/*.zip` through **Settings | Plugins | ⚙ | Install
Plugin from Disk**.

### Connecting an agent

The MCP server here *is* the IDE — there is no separate process to launch, and it
listens on a port assigned per IDE instance. Do not hand-write the port:

1. **Settings | Tools | MCP Server**, enable the server.
2. Use the client entry for your agent to configure it, or **Copy SSE Config**.

### Teaching the agent to use it

A connected agent still reaches for `grep` unless it knows these tools exist.
[`clients/`](clients) carries four skills — navigation, interfaces, refactoring,
testing — as a Claude Code plugin and as Codex skills:

```
/plugin marketplace add salatmaster/goland-mcp-plus
/plugin install go-mcp-plus@go-mcp-plus
```

See [clients/README.md](clients/README.md) for Codex and for what the skills contain.

---

## Requirements

- GoLand 2026.2 or later (build 262+)
- The bundled MCP Server plugin, enabled
- The bundled Go plugin

The plugin builds on the Go plugin's internal API, which carries no compatibility
guarantees. Rather than pin an upper bound — which would make the plugin
uninstallable the moment you update the IDE — a guard turns an incompatible API
into an ordinary tool error naming the IDE build, so a bug report says what broke.

---

## Deliberate omissions

**No `go_extract_function`.** The IDE's extract-function handler needs an `Editor`
and then puts up an inplace naming template, which nothing can answer from a tool
call. Driving it would mean hijacking the editor selection of whoever is sitting at
the keyboard. The alternative — splicing text ourselves — silently gets captured
variables wrong, which is worse than the tool not existing.

**No tool filter UI.** The IDE already ships one at **Settings | Tools | MCP Server
| MCP Tool Filter**, and these tools appear in it. A second switch for the same
thing is only a way for the two to disagree.

**No telemetry.** **Settings | Tools | Go MCP++** shows how the agent has used the
tools this session — calls, failures, cancellations, average duration. It is held
in memory, never written to disk, and never transmitted. A tool sitting at zero
calls usually means the agent was never told it exists.

---

## Development

```bash
./gradlew test                    # 156 tests, no Go SDK required
./gradlew verifyPluginStructure
./gradlew buildPlugin
```

Tests run against a light in-memory fixture, so the suite needs no Go toolchain.

Four architectural rules the suite enforces, each of them a bug that already
happened once:

- **Toolsets never import `com.goide.*`.** All Go PSI access goes through the `go`
  package, so a GoLand upgrade breaks one layer instead of every tool.
- **Tools return `@Serializable` classes with properties.** A bare `List<T>` compiles
  and then fails at runtime inside the MCP schema generator.
- **No default parameter values on tools.** With one, a mis-spelled argument name
  silently uses the default instead of failing.
- **Every tool records its usage.** A tool added without it makes the usage table
  quietly under-report rather than fail.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the layout and the conventions.

---

## License

[Apache-2.0](LICENSE)
