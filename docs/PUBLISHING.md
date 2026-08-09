# Publishing

## JetBrains Marketplace

The Marketplace is the distribution channel. Everything else points at it.

**Before the first upload**

- The plugin name may not contain "GoLand" — the listing is **Go MCP++**. This is a
  Marketplace rule, and the upload is rejected rather than warned about.
- `plugin.xml` must carry a description long enough to be a listing, and a vendor
  with a URL. `./gradlew verifyPluginStructure` checks the rules the build does not.
- Run `./gradlew verifyPlugin` against the recommended IDEs and read the report. The
  plugin uses the Go plugin's internal API, so this is where an incompatibility with
  a newer IDE shows up first.

**Uploading**

1. `./gradlew buildPlugin`
2. Upload `build/distributions/*.zip` at
   <https://plugins.jetbrains.com/plugin/add>, category *Tools Integration*.
3. After approval, generate a permanent token and store it as the
   `JETBRAINS_MARKETPLACE_TOKEN` repository secret. Later releases go out through the
   release workflow, which is gated behind the `PUBLISH_TO_MARKETPLACE` repository
   variable so that tagging alone never publishes by accident.

**Signing.** An update to a signed plugin must itself be signed. The release workflow
reads `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` from the
environment; unset, signing is skipped.

## GitHub release

Tagging `v*` runs the release workflow: it verifies, builds, attaches the zip to a
GitHub release with generated notes, and optionally publishes to the Marketplace.

Bump `pluginVersion` in `gradle.properties` and move the `Unreleased` section of
`CHANGELOG.md` under the new version before tagging.

## MCP directories

This is where the honest positioning matters.

**There is no `server.json`, and there should not be.** The official MCP registry
catalogues servers that can be run: an npm package, a container, a remote endpoint.
This plugin is none of those. It contributes tools *to* the MCP server built into a
JetBrains IDE — which is JetBrains' server, listening on a port assigned per IDE
instance. Registering it as though it were an independently runnable server would
describe something that does not exist, and anyone following the entry would find
nothing to start.

What is accurate, and what listings should say:

> **Go MCP++** — a GoLand plugin that adds 24 Go-specific tools to the MCP server
> built into JetBrains IDEs. Not a standalone server: it extends an existing one.
> Install from the JetBrains Marketplace, then connect your agent to the IDE's MCP
> server.

Submit that to directories that accept IDE-side extensions, with a link to the
Marketplace listing and to this repository. If a directory's schema has no room for
"extends an existing server", the entry does not belong there — a listing that
implies a runnable server is a support burden and a broken first experience.

## Claude Code marketplace

`.claude-plugin/marketplace.json` in this repository makes it installable directly:

```
/plugin marketplace add salatmaster/goland-mcp-plus
/plugin install go-mcp-plus@go-mcp-plus
```

Bump `version` in both `.claude-plugin/marketplace.json` and the plugin's own
`plugin.json` when the skills change — Claude Code uses the version as the cache key
and will not otherwise pull the update.

Validate before pushing:

```bash
claude plugin validate . --strict
claude plugin validate ./clients/claude-code/go-mcp-plus --strict
```
