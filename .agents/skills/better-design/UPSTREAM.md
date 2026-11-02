# Better Design skill source

This project-local agent skill is vendored from
[marvkr/better-design](https://github.com/marvkr/better-design/tree/175e7ba49bcf83857abc32933686b5aa3abb197e/skills/better-design)
at commit `175e7ba49bcf83857abc32933686b5aa3abb197e`.

`SKILL.md` and `agents/openai.yaml` are unchanged upstream files. The upstream MIT
license is included in `LICENSE`.

The skill is installed under the repository's `.agents/skills` directory. It does
not install an MCP server or configure credentials. Follow the upstream
[setup instructions](https://github.com/marvkr/better-design#install) to connect
Better Design to your agent, then restart or reconnect the agent session. The
skill's connection checks require the user to review and run that setup.

For BudgetWise, the native Android app uses Jetpack Compose. Paper is the design
canvas. React and HTML examples from Better Design are design references, rather
than Android dependencies.
