---
name: go-interfaces
description: Use for any question about Go interfaces in a project open in GoLand - who implements an interface, which interfaces a type satisfies, why a type does not satisfy one, or generating the methods to make it satisfy one. Use whenever a compiler error says "does not implement", and whenever you are tempted to search for a method name to find implementations.
---

# Go interface satisfaction

Go has no `implements` keyword. A type satisfies an interface by having the
right method set, and nothing in the source records the relationship. That makes
this the one area where text search is not merely slower than a semantic tool
but structurally unable to answer the question.

Tool names arrive namespaced by the MCP server; match on the `go_*` suffix.

## Which tool answers which question

| Question | Tool | Arguments |
| --- | --- | --- |
| Who implements this interface? | `go_implementations` | `interfaceName`, `limit` |
| Which interfaces does this type satisfy? | `go_interfaces_of` | `typeName`, `limit` |
| Why does this type not satisfy that interface? | `go_interface_check` | `typeName`, `interfaceName` |
| Write the methods it is missing | `go_implement_interface` | `typeName`, `interfaceName`, `pointerReceiver`, `apply` |
| Turn a concrete type's methods into an interface | `go_extract_interface` | `typeName`, `interfaceName`, `methodNames`, `path` |

## The pointer receiver trap

This is the mistake to expect, in your own code and in the code you are reading:

```go
func (c *Circle) Area() float64 { ... }

var s Shape = Circle{}   // does not compile
var s Shape = &Circle{}  // compiles
```

Methods declared on `*T` are not in `T`'s method set. `go_interface_check`
reports this directly: `checkedAs` comes back as `*Circle`, and `pointerReceiverOnly`
lists the methods that forced it. Read those two fields before concluding
anything about a "does not implement" error.

## Reading go_interface_check

- `satisfied` — the answer
- `checkedAs` — `T` when value receivers suffice, `*T` when a pointer is needed
- `missingMethods` — declared nowhere on the type
- `pointerReceiverOnly` — present, but only reachable through a pointer
- `signatureMismatches` — present with the wrong signature, each with the
  `required` and `actual` form. Signatures are compared by type rather than by
  text, so `Read(p []byte)` and `Read(b []byte)` are correctly the same
- `hint` — the next step in plain language; empty when the type already satisfies

The Go compiler says only "does not implement". These fields are the diagnosis
it withholds, so quote them rather than paraphrasing.

## Rules that matter

**Never search for a method name to find implementations.** It finds every
unrelated `Close` in the module and misses embedded ones. `go_implementations`
walks the method sets, including methods gained through embedding.

**Before adding a method by hand, run `go_interface_check`.** It tells you
exactly which methods are missing and with which signatures; guessing produces a
method that looks right and still does not satisfy the interface.

**`go_implement_interface` generates only the missing methods**, with the
receiver you ask for. Set `apply` to false first if you want to review before it
touches the file.

**Answering "can I pass this here?" needs `go_interfaces_of`**, not a reading of
the type's declaration — satisfaction is a property of the whole method set,
including embedded types, and it is not visible in one place.
