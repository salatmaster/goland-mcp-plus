# GoLand MCP+

Go semantic tools for the MCP server built into GoLand.

GoLand already exposes file reading, text search, and patching over MCP. What it does not
expose is the Go type system — so an agent cannot ask who implements an interface, or why a
type fails to satisfy one. Go has no `implements` keyword; those answers exist only inside
the IDE.

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

One call, actionable answer, no guessing.

## Tools

| Tool | Answers |
|---|---|
| `go_symbol` | What is this symbol — signature, doc, declaration site, exported or not |
| `go_implementations` | Which types implement this interface, and do they need a pointer |
| `go_interface_check` | Does this type satisfy this interface, and if not, exactly why |

`go_symbol` takes references the way you would write them — `net/http.Client.Do`,
`./internal/store.Store`, `Handler.ServeHTTP`, or a bare name — rather than file
coordinates an agent would have to hunt for first.

## Requirements

- GoLand 2026.2 (build 262.*)
- The bundled MCP Server plugin, enabled under Settings → Tools → MCP Server

The compatibility range is deliberately narrow. The plugin builds on the Go plugin's
internal API, which carries no compatibility guarantees, so each GoLand release is verified
before the range widens.

## Install

```bash
./gradlew buildPlugin
```

Then Settings → Plugins → gear icon → Install Plugin from Disk → pick the zip from
`build/distributions/`.

## How it connects

The MCP server here *is* the IDE — there is no separate process to launch. Enable the MCP
Server in GoLand and point your agent at it: Claude Code auto-configures, other clients use
the stdio configuration shown in the IDE settings.

## Development

```bash
./gradlew test          # 35 tests, no Go SDK required
./gradlew buildPlugin
```

Two architectural rules the test suite enforces:

- **Toolsets never import `com.goide.*`.** All Go PSI access goes through the `go` package,
  so a GoLand upgrade breaks one layer instead of every tool.
- **Tools return `@Serializable` classes with properties.** A bare `List<T>` return compiles
  but fails at runtime inside the MCP schema generator.

## License

Apache-2.0
