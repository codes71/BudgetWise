# Design tooling

The project-local skills and Paper Shaders are accompanied by shared Better
Design MCP configurations for Claude Code, VS Code, and OpenCode. These files
contain environment references, never API keys. Set `BETTER_DESIGN_API_KEY` in
the environment of the client before launching it. Do not paste keys into the
repository or into shell command history.

The existing local credential has been preserved outside Git in
`~/.config/budgetwise/better-design.env`, readable only by its owner. On this
machine, load it in the terminal that will launch the client:

```sh
source ~/.config/budgetwise/better-design.env
claude
# Or: opencode
```

For VS Code, fully exit any existing instance before starting `code .` from
that same terminal so it inherits the variable. Other contributors should use
their own private environment or secret manager. No credentials are provisioned
by this repository. This change preserves the existing credential; it does not
rotate it or change the service quota.

The reference syntax follows each client's documentation:

- [Claude Code](https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcpjson): `${BETTER_DESIGN_API_KEY}`.
- [VS Code](https://code.visualstudio.com/docs/reference/variables-reference#environment-variables): `${env:BETTER_DESIGN_API_KEY}`.
- [OpenCode](https://opencode.ai/docs/config/#env-vars): `{env:BETTER_DESIGN_API_KEY}`.

The local OpenCode plugin retains all Better Design tools, including required
spacing and review tools. Its verification reminders recognize Android Gradle
and web commands and preserve the command's working directory. Native Android
work uses the supported Gradle checks in `CLAUDE.md`; web work runs from
`apps/web`. This repository has no root npm package.

The plugin was copied from the existing local Better Design installation and
modified for this repository. Its original SHA-256 was
`e0c2f0b16a8cda39a9697474358e4ed9b5d5b51386679792bb09b35b5edc4cd3`.
The upstream [MIT license](../.opencode/LICENSE) is retained
alongside the plugin. The skill packages and their pinned sources are unchanged.

Run the tooling regression checks without starting the app or contacting MCP:

```sh
node --test scripts/tests/design-tooling-config.test.mjs scripts/tests/better-design-plugin.test.mjs
```

These checks validate configuration references and plugin hook behavior. They
do not verify a fresh authenticated MCP connection or native UI acceptance.
