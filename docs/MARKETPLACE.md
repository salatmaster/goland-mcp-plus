# Marketplace listing

The copy that lives on the plugin page rather than in the repository. Kept here so it
is reviewed like everything else, and so the next edit starts from what is published.

See [PUBLISHING](PUBLISHING.md) for uploading and releasing.

## Getting Started

Paste into **Getting Started** in the admin panel. It is HTML, and JetBrains asks for
concise, action-oriented, numbered steps rather than a link to a page elsewhere.

```html
<ol>
  <li><b>Turn the MCP server on.</b> Open <b>Settings | Tools | MCP Server</b> and enable it.
  No restart is needed &mdash; the plugin loads dynamically.</li>

  <li><b>Connect your agent.</b> On the same screen, press <b>Auto-Configure</b> and choose your
  client: Claude Code, Codex, Cursor and others are configured for you, and the port stays in
  sync. For anything else, use <b>Copy config: HTTP Stream</b> and paste it into your client.
  Prefer HTTP Stream over SSE, which MCP has deprecated.
  <br/>The MCP server <i>is</i> the IDE, on a port computed for this instance, so never write a
  port by hand: a copied one is wrong, or reaches a different IDE.</li>

  <li><b>Check it worked.</b> Ask your agent to call <code>go_symbol</code> on any type in your
  project. You should get its declaration with a file and a line &mdash; resolved through the Go
  type system, not found by searching text.</li>

  <li><b>Make the agent reach for the tools.</b> A connected agent still falls back to
  <code>grep</code>, which in Go quietly gives wrong answers: interfaces are satisfied
  structurally, so nothing in the source says who implements what. Install the companion skills
  &mdash; in Claude Code, <code>/plugin marketplace add salatmaster/goland-mcp-plus</code> then
  <code>/plugin install goland-mcp-plus@goland-mcp-plus</code>; in Codex,
  <code>codex plugin marketplace add salatmaster/goland-mcp-plus</code> then
  <code>codex plugin add goland-mcp-plus@goland-mcp-plus</code>. For any other agent, paste the
  rules file from
  <a href="https://github.com/salatmaster/goland-mcp-plus/blob/main/clients/claude-code/CLAUDE.md">the
  repository</a> into whatever always-in-context file it has.</li>
</ol>
```

## Plugin Features is not ours to write

The **Plugin Features** section on a listing cannot be filled in, in `plugin.xml` or
anywhere else. JetBrains derives it with
[intellij-feature-extractor](https://github.com/JetBrains/intellij-plugin-verifier/tree/master/intellij-feature-extractor),
which reads the plugin's bytecode and manifest for four kinds of feature, and feeds the
result into IDE plugin recommendations:

| Feature | Where it comes from |
| --- | --- |
| Run configuration | a `ConfigurationType` implementation, its `getId()` |
| File type | a `FileTypeFactory` implementation |
| Facet | a `FacetType` implementation, its id |
| Dependency support | `<dependencySupport kind=… coordinate=… displayName=…/>` in `plugin.xml` |

This plugin declares none of them, and should not: it contributes toolsets to another
plugin's extension point and one application configurable. So the section stays empty,
which costs nothing except the recommendation mechanism — and that mechanism recommends
a plugin when a *specific* library or file type shows up, which is the wrong shape for
something that applies to any Go project.

Declaring a `dependencySupport` coordinate we do not actually support to get the section
populated would put the plugin in front of people whose project has nothing to do with
it. What the listing responds to instead is the description, the tags, screenshots, and
the Getting Started section above.
