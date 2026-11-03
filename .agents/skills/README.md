# BudgetWise Android Compose skills

Installed project-locally on 2026-11-02 after a targeted GitHub search and source inspection. This is a selected, reviewed collection, not an exhaustive mirror of GitHub. The skill collection itself installs no app dependencies, CLI tools, hooks, MCP servers or global skills. The accompanying MCP configurations and corrected OpenCode plugin are documented in [Design tooling](../../docs/design-tooling.md).

Codex discovers the canonical packages in `.agents/skills`. `.claude/skills` points to this same directory for Claude Code. Start a new turn to refresh skill discovery. The lock file records immutable source commits, licenses and file hashes.

## Project constraints

- User requests and the project's AGENTS.md and CLAUDE.md take precedence over third-party advice. Installing a skill does not authorize commits, pushes, PRs, deployments, external messages, architecture migrations or dependency upgrades.
- Continue the Android redesign in the selected Paper file before implementing app UI. Skill examples are references, not a replacement for the product's design source. Better Design requirements still apply when designing or building UI.
- Keep BudgetWise's chosen identity and Burmese typography. Support light and dark modes, readable contrast, font scaling, TalkBack and at least 48dp interactive targets. Roboto, dynamic colors, gradients, cards and stock navigation indicators are product choices, not inherently wrong.
- Verify APIs against the current version catalog before copying code. At installation, the app uses minSdk 26, targetSdk 36, compileSdk 37, Kotlin 2.4.21, Compose BOM 2026.09.00 and Navigation Compose 2.10.2. These are a snapshot, not a dependency upgrade request.
- RuntimeShader needs API 33 guards and a fallback on older supported devices. Blur examples need an appropriate API 31 fallback and a correct backdrop implementation. Respect reduced motion and avoid frame-loop allocation or perpetual decorative animation.
- Current Android documentation and measured results take precedence over blanket optimization rules. Strong skipping changes lambda memoization behavior. Do not add remember, derivedStateOf, stability annotations or modifier caching without a relevant reason. Never mark mutable data immutable to silence reports.
- Use the project's supported build and verification commands. These skills do not authorize running upstream scripts or installing plugin runtimes. Review any script before executing it. Browser and device operation rules still apply.

## Choose the smallest useful set

| Work | Start with | Add when needed |
| --- | --- | --- |
| Paper screen structure and visual direction | android-compose-design | material-3, mobile-android-design |
| Compose components and state | compose-component-design | compose-state-and-effects, compose-agent |
| Light/dark tokens and adaptive layouts | material-3 | adaptive, edge-to-edge |
| Motion design and implementation | animate-compose | compose-animations, review-android-motion, improve-android-motion |
| Accessibility and focus | android-accessibility | compose-focus-navigation |
| Rendering and shader performance | jetpack-compose-performance | compose-performance |
| Navigation and predictive Back | navigation-event | navigation-3 only for requested migration work |
| UI tests and screenshot checks | compose-ui-testing-patterns | testing-setup only for requested setup work |
| Receipt camera UI | camerax | android-accessibility |
| Supplemental implementation reference | compose-expert | Read topic references selectively |

Do not run all 22 workflows on every screen. The overlapping packages offer different references, not 22 independent requirements.

## Compatibility notes

| Package | Local interpretation |
| --- | --- |
| android-compose-design | Negative padding is invalid in Compose. Use offset or deliberate custom layout for overlap. Its instructions to replace Roboto, cards or stock indicators are optional taste guidance. Preserve the upstream MIT notice, including its placeholder author text. |
| mobile-android-design | Dynamic colors are optional. Its Navigation 2.7.7 example predates the typed route API shown alongside it. Use the project's actual Navigation dependency. Its lambda and cancellation guidance needs current API verification. |
| material-3 | Includes web-oriented examples and dated platform statements. Translate design principles into supported native Compose APIs, not CSS. |
| compose-agent | Broad authoring rules are supplemental. Treat architecture and CompositionLocal preferences as choices, not automatic refactoring instructions. |
| compose-expert | Supplemental only. Its PR-review rules overgeneralize derivedStateOf and lambda memoization. Use official and focused guides for correctness. Atomic-design wrappers and auto-init are optional architecture choices. The upstream migration-banner link is repaired to a pinned GitHub URL. |
| compose-performance | Its optional gradle-run sibling is not installed because a custom build wrapper is outside this design task. That link points to pinned upstream reference material; use BudgetWise's existing build tooling. |
| adaptive | Some examples assume Navigation 3 or newer/experimental Compose APIs. No migration or opt-in is implied by installation. |
| navigation-3 | Migration reference only. One upstream recipe, deeplinks-syntheticbackstack.md, links to a missing README.md. This optional broken source link is retained and recorded in the lock file. |
| testing-setup | Only activate for explicit testing setup. Its Hilt, coverage and screenshot-library defaults do not authorize architecture or dependency changes. |
| android-accessibility | MinTouchTargetSize is a conceptual label in the upstream guide, not a verified Compose symbol. Use supported Material touch-target behavior or appropriate sizeIn constraints for custom controls. Avoid duplicate semantics descriptions. |
| review-android-motion | Its isolated assessment agents are appropriate when this review workflow is explicitly invoked. Its taste defaults remain subordinate to product decisions. |

## Sources

All complete skill directories were installed from immutable commits using the skill-installer helper. Root licenses and applicable NOTICE files were included in each standalone package. Each SKILL.md has a clearly marked local integration notice linking here; reference files otherwise remain as upstream published them. Exact provenance and SHA-256 values are in [sources.lock.json](sources.lock.json).

| Repository | Packages | Pinned commit |
| --- | --- | --- |
| [android/skills](https://github.com/android/skills) | 7 | [6daa53e](https://github.com/android/skills/tree/6daa53ef27fa2aa87827d183a85c06cedceca833) |
| [chrisbanes/skills](https://github.com/chrisbanes/skills) | 6 | [d83f6e2](https://github.com/chrisbanes/skills/tree/d83f6e289a07a55026e610c797672f22eff3f022) |
| [rock3r/android-ux-skills](https://github.com/rock3r/android-ux-skills) | 3 | [90fab0a](https://github.com/rock3r/android-ux-skills/tree/90fab0ac4684333c3374796b50ac2436a25a3754) |
| [aldefy/compose-skill](https://github.com/aldefy/compose-skill) | 1 | [954ef54](https://github.com/aldefy/compose-skill/tree/954ef54ea32288fbc90745f012d09d7b791f0d8a) |
| [hamen/material-3-skill](https://github.com/hamen/material-3-skill) | 1 | [14385f2](https://github.com/hamen/material-3-skill/tree/14385f2bf3804d8779f8b4db2604211f1e70b4c1) |
| [hamen/compose_skill](https://github.com/hamen/compose_skill) | 1 | [f815c31](https://github.com/hamen/compose_skill/tree/f815c31d6cc1a4af4ce2796d08eb7d8b506785c9) |
| [wshobson/agents](https://github.com/wshobson/agents) | 1 | [7e2fb55](https://github.com/wshobson/agents/tree/7e2fb559cf4b824b27f1fec49fe88f7511066d26) |
| [NI3singh/android-compose-design-skill](https://github.com/NI3singh/android-compose-design-skill) | 1 | [9a9f6c4](https://github.com/NI3singh/android-compose-design-skill/tree/9a9f6c4f7afff72a4d38304add19b2eb740693e5) |
| [new-silvermoon/awesome-android-agent-skills](https://github.com/new-silvermoon/awesome-android-agent-skills) | 1 | [82900ea](https://github.com/new-silvermoon/awesome-android-agent-skills/tree/82900eacc8dbe13de93c6310af27b9df4b2bd2f6) |

## Examined but not installed

- anhvt52/jetpack-compose-skills: incorrect configuration-change, strong-skipping, Expressive-version and secret-storage advice.
- AThevon/genjutsu graphics and motion packages: incorrect state invalidation and mixed clock examples, plus incomplete reduced-motion handling.
- hamen/compose_skill jetpack-compose-audit: mandatory canonical-sources.md reference is absent; also outside visual design review scope.
- vasilyu1983/AI-Agents-public software-android-design: source unavailable for verification through GitHub, raw content, Git and authenticated repository read.
- yschimke preview tooling: separate CLI/service setup is beyond this project-local skill installation.
- new-silvermoon broad compose-ui and performance-audit packages: overlap with more focused sources and include outdated lambda/modifier optimization advice. Other architecture, migration, networking and emulator packages are outside this design scope.
- android/skills Styles API skill: indexed search result is absent from the pinned repository. Wear, TV, Auto, XR, ink and home-screen widgets are outside this phone app redesign.

## Installation verification

Skill validation compares every installed upstream file against the pinned checkout, validates each skill's frontmatter name and description, checks license/NOTICE preservation, rejects package symlinks and executable scripts inside skill packages, and records local modifications. The accompanying tooling has separate Node regression tests described in [Design tooling](../../docs/design-tooling.md). Existing unrelated source changes were left intact. This collection accompanies the original Better Design skill in the same PR.
