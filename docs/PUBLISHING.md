# Publishing

## The tag is the version

Nothing in the repository pins one. `build.gradle.kts` reads `PLUGIN_VERSION` from
the environment, the release workflow derives it from the tag, and `pluginVersion`
in `gradle.properties` is only the `-dev` fallback local builds carry.

```bash
# move the Unreleased section of CHANGELOG.md under the new version, commit, then
git tag v0.1.0 && git push origin v0.1.0
```

The workflow validates the tag, runs the suite, builds with that version and
attaches the zip to a GitHub release. A tag that is not `vMAJOR.MINOR.PATCH` fails
rather than producing a nonsense version; a pre-release suffix (`v1.2.3-rc.1`) and
the four-component form the Marketplace accepts both pass.

## JetBrains Marketplace

The Marketplace is the distribution channel; everything else points at it.

Before the first upload: the name may not contain "GoLand" — the listing is
**Go MCP++**, and an upload that breaks this is rejected, not warned about. Run
`verifyPluginStructure` for the rest, and `verifyPlugin` against the recommended
IDEs — the Go plugin's internal API is where an incompatibility shows up first.

Upload `build/distributions/*.zip` at <https://plugins.jetbrains.com/plugin/add>,
category *Tools Integration*. After approval, store the token as
`JETBRAINS_MARKETPLACE_TOKEN`; releases then publish through the workflow, gated
behind the `PUBLISH_TO_MARKETPLACE` repository variable so a tag alone never does.

Signing: an update to a signed plugin must itself be signed. The workflow reads
`CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`; unset, it is skipped.

## MCP directories

**There is no `server.json`, and there should not be.** The registry catalogues
servers you can run — an npm package, a container, a remote endpoint. This is none
of those: it contributes tools *to* the server inside a JetBrains IDE. An entry
implying otherwise sends people looking for something to start that does not exist.

What is accurate:

> **Go MCP++** — a GoLand plugin adding 24 Go-specific tools to the MCP server
> built into JetBrains IDEs. Not a standalone server: it extends an existing one.

If a directory's schema has no room for that, the entry does not belong there.

## Claude Code marketplace

`.claude-plugin/marketplace.json` makes the repository installable directly:

```
/plugin marketplace add salatmaster/goland-mcp-plus
/plugin install goland-mcp-plus@goland-mcp-plus
```

Neither manifest carries a `version`, deliberately: Claude Code then falls back to
the commit SHA, so pushing a change to the skills is enough for `/plugin update` to
see it. Validate with `claude plugin validate .` — without `--strict`, whose only
complaint is that missing version.
