# Vendored skills

Copied from [gabrielbr/Gabriel-Deisgn-System](https://github.com/gabrielbr/Gabriel-Deisgn-System)
`.claude/skills/` at **design system 0.30.0** (commit `ecd9954`), as its AGENTS.md requires of every
app on the boilerplate. AGENTS.md here ("Three skills on every screen and flow") says when to use them.

| Skill | Source | Version | Licence |
|---|---|---|---|
| `impeccable/` | [pbakaus/impeccable](https://github.com/pbakaus/impeccable), `plugin/skills/impeccable` at `f5c80432f883a0ee437735305eafc2a9e1bdb7a0` | skill 4.8.0, launcher 0.1.18 | Apache-2.0 (`impeccable/LICENSE`, `impeccable/NOTICE.md`) |
| `graphic-design-fundamentals/` | The owner's claude.ai skill, via the design system (copied there 2026-11-01) | — | The owner's |
| `less-is-more/` | The design system (0.29.0) | 1.0 | The owner's |

Notes

- PulseLoop is Jetpack Compose, so Impeccable audits use `reference/audit.native.md` scored against
  `reference/android.md`. Its `detect` scanner is for web code and does not apply.
- Don't edit these copies. A skill changes in the design-system repo first; its CHANGELOG entry
  (a patch release) says to re-copy, and this file's version line moves with it.
